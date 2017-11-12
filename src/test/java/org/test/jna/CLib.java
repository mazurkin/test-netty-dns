package org.test.jna;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.Arrays;
import java.util.List;

@SuppressWarnings({ "SpellCheckingInspection", "unused" })
public interface CLib extends Library {

    CLib INSTANCE = (CLib) Native.loadLibrary("c", CLib.class);

    class Hostent extends Structure {

        private static final List<String> ORDER = Arrays.asList(
                "name", "aliases", "type", "length", "addresses"
        );

        public Pointer name;
        public Pointer aliases;
        public int type;
        public int length;
        public Pointer addresses;

        public Hostent() {
        }

        public Hostent(Pointer p) {
            super(p);
        }

        @Override
        protected List<String> getFieldOrder() {
            return ORDER;
        }

        public String getName() {
            if (name != Pointer.NULL) {
                return name.getString(0);
            }

            return null;
        }

        public String[] getAliases() {
            if (aliases != Pointer.NULL) {
                return aliases.getStringArray(0);
            }

            return null;
        }

        public byte[][] getAddresses() {
            if (addresses != Pointer.NULL) {
                Pointer[] pointers = addresses.getPointerArray(0);
                if (pointers.length > 0) {
                    byte[][] result = new byte[pointers.length][];

                    for (int i = 0; i < pointers.length; i++) {
                        result[i] = pointers[i].getByteArray(0, length);
                    }

                    return result;
                }
            }

            return null;
        }

    }

    Hostent gethostbyname(String name);

    int gethostbyname_r(String name, Hostent ret, Pointer buf, NativeLong buflen,
                        PointerByReference result, IntByReference h_errnop);

}
