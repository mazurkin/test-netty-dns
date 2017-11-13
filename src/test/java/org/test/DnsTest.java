package org.test;

import com.sun.jna.Platform;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.SingletonDnsServerAddressStreamProvider;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.test.jna.CLibAdapter;
import org.test.direct.NameServices;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TextParseException;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

public class DnsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(DnsTest.class);

    private static final InetSocketAddress DNS_ADDRESS_GOOGLE_PUBLIC = new InetSocketAddress("8.8.8.8", 53);

    private static final InetSocketAddress DNS_ADDRESS_PRIVATE = new InetSocketAddress("10.201.50.60", 53);

    private static final InetSocketAddress DNS_ADDRESS_LOCAL_UBUNTU = new InetSocketAddress("127.0.1.1", 53);

    private static final InetSocketAddress DNS_ADDRESS_LOCAL_PDNSD = new InetSocketAddress("127.0.0.1", 10053);

    private static final InetSocketAddress DNS_ADDRESS = DNS_ADDRESS_LOCAL_UBUNTU;

    private static final int NETTY_POOL_THREADS = 1;

    private static final int TIMEOUT_SEC = 20;

    private static final int MAX_QUERIES = 20;

    private static final int MAX_PAYLOAD = 64 * 1024;

    private static final int DOMAIN_COUNT = 1000;

    private static final int CONCURRENCY = 64;

    private static List<String> domains;

    private EventLoopGroup nettyGroup;

    private DnsNameResolver nettyResolver;

    private SimpleResolver dnsJavaTcpResolver;

    private SimpleResolver dnsJavaUdpResolver;

    @BeforeClass
    public static void setUpClass() throws Exception {
        try (InputStream is = DnsTest.class.getResourceAsStream("/crawler_domains.txt.gz")) {
            try (GZIPInputStream gis = new GZIPInputStream(is)) {
                domains = IOUtils.readLines(gis, StandardCharsets.UTF_8);
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        nettyGroup = new EpollEventLoopGroup(NETTY_POOL_THREADS, new DefaultThreadFactory("DNS pool"));

        SingletonDnsServerAddressStreamProvider dnsServerProvider =
                new SingletonDnsServerAddressStreamProvider(DNS_ADDRESS);

        nettyResolver = new DnsNameResolverBuilder(nettyGroup.next())
                .channelType(EpollDatagramChannel.class)
                .queryTimeoutMillis(TimeUnit.SECONDS.toMillis(TIMEOUT_SEC))
                .maxQueriesPerResolve(MAX_QUERIES)
                .maxPayloadSize(MAX_PAYLOAD)
                .resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY)
                .nameServerProvider(dnsServerProvider)
                .build();

        dnsJavaTcpResolver = new SimpleResolver();
        dnsJavaTcpResolver.setAddress(DNS_ADDRESS);
        dnsJavaTcpResolver.setTimeout(TIMEOUT_SEC);
        dnsJavaTcpResolver.setTCP(true);

        dnsJavaUdpResolver = new SimpleResolver();
        dnsJavaUdpResolver.setAddress(DNS_ADDRESS);
        dnsJavaUdpResolver.setTimeout(TIMEOUT_SEC);
        dnsJavaUdpResolver.setTCP(false);
    }

    @After
    public void tearDown() throws Exception {
        if (nettyGroup != null) {
            nettyGroup.shutdownGracefully().sync();
        }

        if (nettyResolver != null) {
            nettyResolver.close();
        }
    }

    @Test
    public void remoteNettyAsyncDns() throws Exception {
        resolveDnsAsync(domain -> resolveNettyAsync(domain, nettyResolver));
    }

    @Test
    public void systemJavaSyncDns() throws Exception {
        resolveDnsSync(DnsTest::resolveSystemSync);
    }

    @Test
    public void systemDirectSyncDns() throws Exception {
        resolveDnsSync(DnsTest::resolveSystemSyncDirect);
    }

    @Test
    public void systemJnaSyncDns1() throws Exception {
        Assume.assumeTrue(Platform.isLinux());
        resolveDnsSync(DnsTest::resolveSystemSyncJna1);
    }

    @Test
    public void systemJnaSyncDns2() throws Exception {
        Assume.assumeTrue(Platform.isLinux());
        resolveDnsSync(DnsTest::resolveSystemSyncJna2);
    }

    @Test
    public void remoteJavaLibTcpSyncDns() throws Exception {
        resolveDnsSync(domain -> resolveSimpleSync(domain, dnsJavaTcpResolver));
    }

    @Test
    public void remoteJavaLibUdpSyncDns() throws Exception {
        resolveDnsSync(domain -> resolveSimpleSync(domain, dnsJavaUdpResolver));
    }

    @Ignore("run `src/test/resources/crawler_domains-adnshost.sh`")
    @Test
    public void systemADnsHostCommandDns() throws Exception {
        // see the comment above
    }

    private static void resolveDnsSync(Function<String, InetAddress> resolver)
            throws InterruptedException
    {
        LOGGER.info("Started resolving {} domains", DOMAIN_COUNT);

        ExecutorService executorService = Executors.newFixedThreadPool(CONCURRENCY);

        AtomicInteger succeed = new AtomicInteger(0);

        for (String domain : domains.subList(0, DOMAIN_COUNT)) {
            executorService.submit(() -> {
                InetAddress address = resolver.apply(domain);
                if (address != null) {
                    succeed.incrementAndGet();
                    LOGGER.trace("{} = [{}]", domain, address.getHostAddress());
                } else {
                    LOGGER.trace("{} = NONE", domain);
                }

                return true;
            });
        }

        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.MINUTES);

        LOGGER.info("Succeed {} domains", succeed.get());
    }

    private static void resolveDnsAsync(Function<String, CompletableFuture<InetAddress>> resolver)
            throws InterruptedException
    {
        LOGGER.info("Started resolving {} domains", DOMAIN_COUNT);

        BlockingQueue<DnsResult> queue = new LinkedBlockingDeque<>();

        Semaphore semaphore = new Semaphore(CONCURRENCY);

        // Schedule async DNS resolves
        for (String domain : domains.subList(0, DOMAIN_COUNT)) {
            semaphore.acquire();

            CompletableFuture<InetAddress> future = resolver.apply(domain);

            future.whenComplete((address, ex) -> {
                semaphore.release();

                DnsResult result = new DnsResult();
                result.domain = domain;

                if (ex == null) {
                    result.resolved = true;
                    result.message = address.getHostAddress();
                } else {
                    result.resolved = false;
                    result.message = ex.getClass().getCanonicalName() + " / " + ex.getLocalizedMessage();
                }

                queue.add(result);
            });
        }

        LOGGER.info("All requests are published");

        // Wait pending results and check
        int fn = 0;
        int tn = 0;
        int p  = 0;

        for (int i = 0; i < DOMAIN_COUNT; i++) {
            DnsResult result = queue.take();

            if (result.resolved) {
                LOGGER.trace("{} Y {} : [{}]", i, result.domain, result.message);
                p++;
            } else {
                LOGGER.trace("{} N {} : {}", i, result.domain, result.message);

                InetAddress realAddress = resolveSystemSync(result.domain);
                if (realAddress != null) {
                    fn++;
                    LOGGER.trace("    !!! SYNC [{}]", realAddress.getHostAddress());
                } else {
                    tn++;
                }
            }
        }

        // Output statistics
        LOGGER.info("False negatives : {}", fn);
        LOGGER.info("True negatives  : {}", tn);
        LOGGER.info("Succeed         : {}", p);
    }

    private static CompletableFuture<InetAddress> resolveNettyAsync(String domain, DnsNameResolver nettyResolver) {
        return future(nettyResolver.resolve(domain));
    }

    private static InetAddress resolveSystemSync(String domain) {
        // Uses JVM/system DNS resolver
        try {
            return InetAddress.getByName(domain);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static InetAddress resolveSystemSyncDirect(String domain) {
        // Uses JVM/system DNS resolver (no caching, no sync)
        try {
            InetAddress[] addresses = NameServices.resolve(domain);
            if (addresses != null && addresses.length > 0) {
                return addresses[0];
            } else {
                return null;
            }
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static InetAddress resolveSystemSyncJna1(String domain) {
        // Uses JVM/system DNS resolver (no caching, no sync)
        try {
            InetAddress[] addresses = CLibAdapter.resolve1(domain);
            return addresses[0];
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static InetAddress resolveSystemSyncJna2(String domain) {
        // Uses JVM/system DNS resolver (no caching, no sync)
        try {
            InetAddress[] addresses = CLibAdapter.resolve2(domain);
            return addresses[0];
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static InetAddress resolveSimpleSync(String domain, Resolver resolver) {
        Lookup lookup;
        try {
            lookup = new Lookup(domain);
        } catch (TextParseException e) {
            return null;
        }

        lookup.setResolver(resolver);

        Record[] records = lookup.run();
        if (lookup.getResult() == Lookup.SUCCESSFUL && records != null && records.length > 0) {
            return ((ARecord) records[0]).getAddress();
        } else {
            return null;
        }
    }

    private static <T> CompletableFuture<T> future(io.netty.util.concurrent.Future<T> future) {
        CompletableFuture<T> result = new CompletableFuture<T>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return super.cancel(mayInterruptIfRunning) && future.cancel(mayInterruptIfRunning);
            }
        };

        future.addListener((f) -> {
            if (future.isSuccess()) {
                result.complete(future.get());
            } else if (!future.isCancelled()) {
                result.completeExceptionally(future.cause());
            }
        });

        return result;
    }

    private static final class DnsResult {

        private String domain;

        private String message;

        private boolean resolved;

    }


}
