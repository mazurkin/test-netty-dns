package org.test.jna;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class CLibTest {

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue(Platform.isLinux());

        System.out.println(Native.getDefaultStringEncoding());
    }

    @Test
    public void testResolveKnown() throws Exception {
        InetAddress[] addresses = CLibAdapter.resolve1("google.com");
        Assert.assertNotNull(addresses);
        Assert.assertTrue(addresses.length > 0);
    }

    @Test(expected = UnknownHostException.class)
    public void testResolveUnknown() throws Exception {
        CLibAdapter.resolve1("google3-rwgwrgwr.com");
    }

    @Test
    public void testResolveKnown2() throws Exception {
        InetAddress[] addresses = CLibAdapter.resolve2("google.com");
        Assert.assertNotNull(addresses);
        Assert.assertTrue(addresses.length > 0);
    }

    @Test(expected = UnknownHostException.class)
    public void testResolveUnknown2() throws Exception {
        CLibAdapter.resolve2("google3-rwgwrgwr.com");
    }

    @Test
    public void test2() throws Exception {
        InetAddress[] addresses = CLibAdapter.resolve2("letsbytecode.com");
        Assert.assertNotNull(addresses);
        Assert.assertTrue(addresses.length > 0);
    }

    @Test
    public void test3() throws Exception {
        InetAddress[] addresses = CLibAdapter.resolve2("54.77.84.201");
        Assert.assertNotNull(addresses);
        Assert.assertTrue(addresses.length > 0);
    }
}
