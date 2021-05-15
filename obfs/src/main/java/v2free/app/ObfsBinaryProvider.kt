package v2free.app

import android.net.Uri
import android.os.ParcelFileDescriptor
import com.github.shadowsocks.plugin.NativePluginProvider
import com.github.shadowsocks.plugin.PathProvider
import java.io.File
import java.io.FileNotFoundException

class ObfsBinaryProvider : NativePluginProvider() {
    override fun populateFiles(provider: PathProvider) {
        provider.addPath("obfs-local", 755)
    }

    override fun getExecutable() = context!!.applicationInfo.nativeLibraryDir + "/libobfs-local.so"
    override fun openFile(uri: Uri): ParcelFileDescriptor = when (uri.path) {
        "/obfs-local" -> ParcelFileDescriptor.open(File(getExecutable()), ParcelFileDescriptor.MODE_READ_ONLY)
        else -> throw FileNotFoundException()
    }
}