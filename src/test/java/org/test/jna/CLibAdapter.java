package org.test.jna;

import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class CLibAdapter {

    // https://github.com/java-native-access/jna/blob/master/www/Mappings.md
    // https://usrmisc.wordpress.com/2012/12/27/integer-sizes-in-c-on-32-bit-and-64-bit-linux/

    private static final Object MONITOR_NET_DB = new Object();

    private static final int RESOLVE_BUFFER = 4096;

    public static InetAddress[] resolve1(String host) throws UnknownHostException {
        // gethostbyname is not thread safe
        // http://man7.org/linux/man-pages/man3/gethostbyname.3.html
        synchronized (MONITOR_NET_DB) {
            CLib.Hostent entry = CLib.INSTANCE.gethostbyname(host);

            return getEntryAddresses(host, entry);
        }
    }

    public static InetAddress[] resolve2(String host) throws UnknownHostException {
        CLib.Hostent template = new CLib.Hostent();
        PointerByReference result = new PointerByReference();
        IntByReference errCode = new IntByReference(-1);

        long buffer = Native.malloc(RESOLVE_BUFFER);
        try {
            // gethostbyname_r is thread safe
            // http://man7.org/linux/man-pages/man3/gethostbyname.3.html
            int r = CLib.INSTANCE.gethostbyname_r(host, template,
                    new Pointer(buffer), new NativeLong(RESOLVE_BUFFER), result, errCode);
            if (r != 0) {
                throw new UnknownHostException("Can't resolve " + host + " - return code is " + r);
            }

            if (errCode.getValue() != 0) {
                throw new UnknownHostException("Can't resolve " + host + " - error code is " + errCode.getValue());
            }

            if (result.getValue() == Pointer.NULL) {
                throw new UnknownHostException("Can't resolve " + host + " - result is null");
            }

            CLib.Hostent resultEntry = new CLib.Hostent(result.getValue());
            resultEntry.read();

            return getEntryAddresses(host, resultEntry);
        } finally {
            Native.free(buffer);
        }
    }

    private static InetAddress[] getEntryAddresses(String host, CLib.Hostent entry) throws UnknownHostException {
        if (entry == null) {
            throw new UnknownHostException("Can't resolve " + host + " - result is null");
        }

        if (entry.type != CLibConsts.AF_INET) {
            throw new UnknownHostException("Can't resolve " + host + " - unknown family " + entry.type);
        }

        if (entry.length != 4) {
            throw new UnknownHostException("Can't resolve " + host + " - wrong length " + entry.length);
        }

        byte[][] addresses = entry.getAddresses();
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException("Can't resolve " + host + " - no addresses");
        }

        InetAddress[] result = new InetAddress[addresses.length];
        for (int i = 0; i < addresses.length; i++) {
            result[i] = InetAddress.getByAddress(addresses[i]);
        }

        return result;
    }
    
}
