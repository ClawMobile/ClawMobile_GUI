package com.termux.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DnsResolver;
import android.net.Network;
import android.os.Build;
import android.os.CancellationSignal;

import androidx.annotation.NonNull;

import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class ClawMobileHostResolver {

    private static final String[] PUBLIC_DNS_SERVERS = {
        "1.1.1.1",
        "8.8.8.8"
    };

    private static final String[] REQUIRED_HOSTS = {
        "packages-cf.termux.dev",
        "ports.ubuntu.com",
        "openclaw.ai",
        "registry.npmjs.org",
        "pypi.org",
        "files.pythonhosted.org",
        "crates.io",
        "index.crates.io",
        "static.crates.io",
        "github.com",
        "raw.githubusercontent.com"
    };

    private ClawMobileHostResolver() {}

    @NonNull
    static List<String> resolveHostAddresses(@NonNull Context context, @NonNull String host) {
        return resolveHost(context, host);
    }

    static void seedResolvedHosts(@NonNull Context context, @NonNull File repoDir) throws IOException {
        LinkedHashMap<String, List<String>> hostMappings = resolveHostMappings(context);
        List<String> termuxRepoAddresses = hostMappings.get("packages-cf.termux.dev");
        if (termuxRepoAddresses == null || termuxRepoAddresses.isEmpty()) {
            throw new IOException("Failed to resolve packages-cf.termux.dev from app network stack");
        }

        File resolvedHostsFile = new File(new File(repoDir, ".clawmobile"), "resolved-hosts");
        writeResolvedHostsFile(resolvedHostsFile, hostMappings);
        writeTermuxHostsFile(new File(TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH, "hosts"), hostMappings);
    }

    @NonNull
    private static LinkedHashMap<String, List<String>> resolveHostMappings(@NonNull Context context) {
        LinkedHashMap<String, List<String>> mappings = new LinkedHashMap<>();
        for (String host : REQUIRED_HOSTS) {
            List<String> addresses = resolveHost(context, host);
            if (!addresses.isEmpty()) {
                mappings.put(host, addresses);
            }
        }
        return mappings;
    }

    @NonNull
    private static List<String> resolveHost(@NonNull Context context, @NonNull String host) {
        LinkedHashSet<String> addresses = new LinkedHashSet<>();
        addAddresses(addresses, queryWithPublicDns(host));
        addAddresses(addresses, queryWithDnsResolver(context, host));
        if (addresses.isEmpty()) {
            addAddresses(addresses, queryWithInetAddress(host));
        }
        return new ArrayList<>(addresses);
    }

    private static void addAddresses(@NonNull LinkedHashSet<String> addresses,
                                     @NonNull List<InetAddress> inetAddresses) {
        boolean addedIpv4 = false;
        for (InetAddress inetAddress : inetAddresses) {
            if (!isUsableAddress(inetAddress)) {
                continue;
            }

            if (inetAddress instanceof Inet4Address) {
                addresses.add(inetAddress.getHostAddress());
                addedIpv4 = true;
            }
        }

        if (!addedIpv4) {
            for (InetAddress inetAddress : inetAddresses) {
                if (!isUsableAddress(inetAddress)) {
                    continue;
                }
                addresses.add(inetAddress.getHostAddress());
            }
        }
    }

    @NonNull
    private static List<InetAddress> queryWithPublicDns(@NonNull String host) {
        for (String dnsServer : PUBLIC_DNS_SERVERS) {
            List<InetAddress> answers = queryPublicDnsServer(host, dnsServer);
            if (!answers.isEmpty()) {
                return answers;
            }
        }

        return new ArrayList<>();
    }

    @NonNull
    private static List<InetAddress> queryPublicDnsServer(@NonNull String host, @NonNull String dnsServer) {
        DatagramSocket socket = null;
        try {
            int requestId = (int) (System.nanoTime() & 0xffff);
            byte[] request = buildDnsQuery(host, requestId);

            socket = new DatagramSocket();
            socket.connect(new InetSocketAddress(dnsServer, 53));
            socket.setSoTimeout(2000);

            DatagramPacket requestPacket = new DatagramPacket(request, request.length);
            socket.send(requestPacket);

            byte[] responseBuffer = new byte[1500];
            DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(responsePacket);

            return parseDnsResponse(responsePacket.getData(), responsePacket.getLength(), requestId);
        } catch (IOException e) {
            return new ArrayList<>();
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    @NonNull
    private static List<InetAddress> queryWithDnsResolver(@NonNull Context context, @NonNull String host) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return new ArrayList<>();
        }

        ConnectivityManager connectivityManager =
            (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return new ArrayList<>();
        }

        Network network = connectivityManager.getActiveNetwork();
        if (network == null) {
            return new ArrayList<>();
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<InetAddress>> answerRef = new AtomicReference<>(new ArrayList<>());
        AtomicReference<Throwable> errorRef = new AtomicReference<>(null);
        CancellationSignal cancellationSignal = new CancellationSignal();

        DnsResolver.getInstance().query(network, host, DnsResolver.FLAG_EMPTY, Runnable::run,
            cancellationSignal, new DnsResolver.Callback<List<InetAddress>>() {
                @Override
                public void onAnswer(@NonNull List<InetAddress> answer, int rcode) {
                    answerRef.set(new ArrayList<>(answer));
                    latch.countDown();
                }

                @Override
                public void onError(@NonNull DnsResolver.DnsException error) {
                    errorRef.set(error);
                    latch.countDown();
                }
            });

        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                cancellationSignal.cancel();
                return new ArrayList<>();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancellationSignal.cancel();
            return new ArrayList<>();
        }

        if (errorRef.get() != null) {
            return new ArrayList<>();
        }

        return answerRef.get();
    }

    @NonNull
    private static List<InetAddress> queryWithInetAddress(@NonNull String host) {
        try {
            InetAddress[] inetAddresses = InetAddress.getAllByName(host);
            ArrayList<InetAddress> results = new ArrayList<>(inetAddresses.length);
            for (InetAddress inetAddress : inetAddresses) {
                results.add(inetAddress);
            }
            return results;
        } catch (UnknownHostException e) {
            return new ArrayList<>();
        }
    }

    private static boolean isUsableAddress(@NonNull InetAddress inetAddress) {
        if (inetAddress.isAnyLocalAddress() || inetAddress.isLoopbackAddress() ||
            inetAddress.isLinkLocalAddress() || inetAddress.isMulticastAddress()) {
            return false;
        }

        if (!(inetAddress instanceof Inet4Address)) {
            return !inetAddress.isSiteLocalAddress();
        }

        byte[] bytes = inetAddress.getAddress();
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        int third = bytes[2] & 0xff;

        if (first == 0 || first == 10 || first == 127) return false;
        if (first == 100 && second >= 64 && second <= 127) return false;
        if (first == 169 && second == 254) return false;
        if (first == 172 && second >= 16 && second <= 31) return false;
        if (first == 192 && second == 168) return false;
        if (first == 198 && (second == 18 || second == 19)) return false;
        if (first == 192 && second == 0 && third == 2) return false;
        if (first == 198 && second == 51 && third == 100) return false;
        if (first == 203 && second == 0 && third == 113) return false;
        if (first >= 224) return false;

        return true;
    }

    @NonNull
    private static byte[] buildDnsQuery(@NonNull String host, int requestId) throws IOException {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(byteStream);

        out.writeShort(requestId & 0xffff);
        out.writeShort(0x0100);
        out.writeShort(1);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);

        String[] labels = host.split("\\.");
        for (String label : labels) {
            byte[] labelBytes = label.getBytes(StandardCharsets.US_ASCII);
            out.writeByte(labelBytes.length);
            out.write(labelBytes);
        }
        out.writeByte(0);
        out.writeShort(1);
        out.writeShort(1);
        out.flush();

        return byteStream.toByteArray();
    }

    @NonNull
    private static List<InetAddress> parseDnsResponse(@NonNull byte[] response, int responseLength, int requestId)
        throws IOException {
        ArrayList<InetAddress> answers = new ArrayList<>();
        if (responseLength < 12) {
            return answers;
        }

        int responseId = readUInt16(response, 0);
        if (responseId != (requestId & 0xffff)) {
            return answers;
        }

        int flags = readUInt16(response, 2);
        int rcode = flags & 0x000f;
        if (rcode != 0) {
            return answers;
        }

        int questionCount = readUInt16(response, 4);
        int answerCount = readUInt16(response, 6);
        int offset = 12;

        for (int i = 0; i < questionCount; i++) {
            offset = skipDnsName(response, responseLength, offset);
            if (offset < 0 || offset + 4 > responseLength) {
                return answers;
            }
            offset += 4;
        }

        for (int i = 0; i < answerCount; i++) {
            offset = skipDnsName(response, responseLength, offset);
            if (offset < 0 || offset + 10 > responseLength) {
                return answers;
            }

            int type = readUInt16(response, offset);
            int recordClass = readUInt16(response, offset + 2);
            int rdLength = readUInt16(response, offset + 8);
            offset += 10;

            if (offset + rdLength > responseLength) {
                return answers;
            }

            if (type == 1 && recordClass == 1 && rdLength == 4) {
                byte[] addressBytes = new byte[] {
                    response[offset],
                    response[offset + 1],
                    response[offset + 2],
                    response[offset + 3]
                };
                answers.add(InetAddress.getByAddress(addressBytes));
            }

            offset += rdLength;
        }

        return answers;
    }

    private static int skipDnsName(@NonNull byte[] response, int responseLength, int offset) {
        while (offset < responseLength) {
            int length = response[offset] & 0xff;
            if (length == 0) {
                return offset + 1;
            }

            if ((length & 0xc0) == 0xc0) {
                if (offset + 1 >= responseLength) {
                    return -1;
                }
                return offset + 2;
            }

            offset += length + 1;
        }

        return -1;
    }

    private static int readUInt16(@NonNull byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static void writeResolvedHostsFile(@NonNull File targetFile,
                                               @NonNull LinkedHashMap<String, List<String>> hostMappings)
        throws IOException {
        writeHostFile(targetFile, hostMappings, false);
    }

    private static void writeTermuxHostsFile(@NonNull File targetFile,
                                             @NonNull LinkedHashMap<String, List<String>> hostMappings)
        throws IOException {
        writeHostFile(targetFile, hostMappings, true);
    }

    private static void writeHostFile(@NonNull File targetFile,
                                      @NonNull LinkedHashMap<String, List<String>> hostMappings,
                                      boolean includeLocalhostEntries) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory " + parent.getAbsolutePath());
        }

        StringBuilder builder = new StringBuilder();
        if (includeLocalhostEntries) {
            builder.append("127.0.0.1 localhost\n");
            builder.append("::1 ip6-localhost\n");
        }

        for (Map.Entry<String, List<String>> entry : hostMappings.entrySet()) {
            for (String address : entry.getValue()) {
                builder.append(address).append(' ').append(entry.getKey()).append('\n');
            }
        }

        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            fos.write(builder.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
