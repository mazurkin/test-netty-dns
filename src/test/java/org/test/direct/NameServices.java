package org.test.direct;

import sun.net.spi.nameservice.NameService;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class NameServices {

    private static final List<NameService> NAME_SERVICES = getNameServices();

    public static InetAddress[] resolveFallback(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    public static InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] result = null;
        Collection<UnknownHostException> suppressedList = null;

        for (NameService nameService : NAME_SERVICES) {
            try {
                result = nameService.lookupAllHostAddr(host);
            } catch (UnknownHostException e) {
                if (suppressedList == null) {
                    suppressedList = new ArrayList<>(NAME_SERVICES.size());
                }
                suppressedList.add(e);
            }
        }

        if (result != null && result.length > 0) {
            return result;
        } else {
            UnknownHostException ex = new UnknownHostException("Fail to resolve host " + host);
            if (suppressedList != null) {
                for (UnknownHostException suppressed : suppressedList) {
                    ex.addSuppressed(suppressed);
                }
            }
            throw ex;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<NameService> getNameServices() {
        try {
            Field f = InetAddress.class.getDeclaredField("nameServices");
            f.setAccessible(true);

            return (List<NameService>) f.get(null);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
