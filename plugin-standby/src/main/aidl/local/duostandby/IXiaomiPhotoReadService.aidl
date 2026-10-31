package local.duostandby;
import android.os.ParcelFileDescriptor;
interface IXiaomiPhotoReadService {
    void destroy() = 16777114;
    int getUid() = 1;
    ParcelFileDescriptor openPhoto(String imageId) = 2;
    String readCapture(String imageId) = 3;
}
