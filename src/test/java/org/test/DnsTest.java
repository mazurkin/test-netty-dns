package org.test;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.SingletonDnsServerAddressStreamProvider;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

public class DnsTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(DnsTest.class);

    // GOOGLE public DNS
    private static final InetSocketAddress DNS_ADDRESS = new InetSocketAddress("8.8.8.8", 53);

    // my private DNS
    // private static final InetSocketAddress DNS_ADDRESS = new InetSocketAddress("10.201.50.60", 53);

    // UBUNTU local DNS relay
    // private static final InetSocketAddress DNS_ADDRESS = new InetSocketAddress("127.0.1.1", 53);

    // UBUNTU local PDNSD relay
    // private static final InetSocketAddress DNS_ADDRESS = new InetSocketAddress("127.0.0.1", 1053);

    private static final int THREADS = 1;

    private static final long TIMEOUT_MS = TimeUnit.MINUTES.toMillis(3);

    private static final int MAX_QUERIES = 20;

    private static final int MAX_PAYLOAD = 64 * 1024;

    private static List<String> domains;

    private DnsNameResolver resolver;

    private EventLoopGroup group;

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
        group = new NioEventLoopGroup(THREADS, new DefaultThreadFactory("DNS pool"));

        SingletonDnsServerAddressStreamProvider dnsServerProvider =
                new SingletonDnsServerAddressStreamProvider(DNS_ADDRESS);

        resolver = new DnsNameResolverBuilder(group.next())
                .channelType(NioDatagramChannel.class)
                .queryTimeoutMillis(TIMEOUT_MS)
                .recursionDesired(false)
                .optResourceEnabled(false)
                .decodeIdn(true)
                .maxQueriesPerResolve(MAX_QUERIES)
                .maxPayloadSize(MAX_PAYLOAD)
                .resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY)
                .nameServerProvider(dnsServerProvider)
                .build();
    }

    @After
    public void tearDown() throws Exception {
        if (group != null) {
            group.shutdownGracefully().sync();
        }

        if (resolver != null) {
            resolver.close();
        }
    }

    @Test
    public void testAsyncDns() throws Exception {
        BlockingQueue<DnsResult> queue = new ArrayBlockingQueue<>(domains.size());

        // int count = domains.size();

        int count = 50;
        LOGGER.debug("Started resolving {} domains", count);

        // Schedule async DNS resolves
        for (String domain : domains.subList(0, count)) {
            CompletableFuture<InetAddress> future = resolveAsync(domain);

            future.whenComplete((address, ex) -> {
                DnsResult result = new DnsResult();
                result.domain = domain;

                if (ex == null) {
                    result.resolved = true;
                    result.message = address.getHostAddress();
                } else {
                    result.resolved = false;
                    result.message = ex.getLocalizedMessage();

                    LOGGER.trace("Exception on DNS resolving", ex);
                }

                queue.add(result);
            });
        }

        // Wait pending results
        int fn = 0;
        int tn = 0;

        for (int i = 0; i < count; i++) {
            DnsResult result = queue.take();

            if (result.resolved) {
                LOGGER.info("{} Y {} : [{}]", i, result.domain, result.message);
            } else {
                LOGGER.info("{} N {} : {}", i, result.domain, result.message);

                InetAddress realAddress = resolveSync(result.domain);
                if (realAddress != null) {
                    fn++;
                    LOGGER.info("    !!! SYNC [{}]", realAddress.getHostAddress());
                } else {
                    tn++;
                }
            }
        }

        // Output statistics
        LOGGER.debug("False negatives : {}", fn);
        LOGGER.debug("True negatives  : {}", tn);
    }

    @Test
    public void testSyncDns() throws Exception {
        int i = 0;

        for (String domain : domains) {
            InetAddress address = resolveSync(domain);

            if (address != null) {
                LOGGER.info("{}\t{} = [{}]", i, domain, address.getHostAddress());
            } else {
                LOGGER.info("{}\t{} = NONE", i, domain);
            }

            i++;
        }
    }

    @Test
    public void testAsyncCname() throws Exception {
        CompletableFuture<InetAddress> future = resolveAsync("fsnusantara.blogspot.fr");

        try {
            InetAddress address = future.get();
            LOGGER.info("Successfully resolved {}", address);
        } catch (Exception e) {
            LOGGER.error("Fail to resolve address", e);
        }
    }

    @Test
    public void testSyncCname() throws Exception {
        try {
            InetAddress address = resolveSync("fsnusantara.blogspot.fr");
            LOGGER.info("Successfully resolved {}", address);
        } catch (Exception e) {
            LOGGER.error("Fail to resolve address", e);
        }
    }

    public CompletableFuture<InetAddress> resolveAsync(String domain) {
        return future(resolver.resolve(domain));
    }
    
    private static InetAddress resolveSync(String domain) {
        try {
            // Uses JVM/system DNS resolver
            return InetAddress.getByName(domain);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    public static <T> CompletableFuture<T> future(io.netty.util.concurrent.Future<T> future) {
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
            } else {
                LOGGER.warn("Looks like the feature is canceled");
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