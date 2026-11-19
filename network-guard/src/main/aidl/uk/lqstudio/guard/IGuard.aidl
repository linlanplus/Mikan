package uk.lqstudio.guard;
import android.net.Network;
import android.os.ParcelFileDescriptor;
interface IGuard {
    ParcelFileDescriptor tunnel();
    ParcelFileDescriptor dial(String ip, int port, boolean udp);
    String[] resolve(String name);
    Network network();
}
