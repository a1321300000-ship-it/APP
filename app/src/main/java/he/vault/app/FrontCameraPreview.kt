package he.vault.app

import android.content.Context
import android.hardware.Camera
import android.view.SurfaceHolder
import android.view.SurfaceView

@Suppress("DEPRECATION")
class FrontCameraPreview(
    context: Context,
    private val onPhoto: (ByteArray) -> Unit,
    private val onUnavailable: (String) -> Unit,
    private val onReady: () -> Unit
) : SurfaceView(context), SurfaceHolder.Callback {
    private var camera: Camera? = null
    private var photoRequested = false
    private var photoTaken = false

    init {
        holder.addCallback(this)
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)
        keepScreenOn = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        try {
            val cameraId = findFrontCamera()
            if (cameraId < 0) {
                onUnavailable("לא נמצאה מצלמה קדמית במכשיר")
                return
            }
            val opened = Camera.open(cameraId)
            camera = opened
            opened.setDisplayOrientation(90)
            val parameters = opened.parameters
            parameters.supportedPreviewSizes
                ?.filter { it.width <= 1280 && it.height <= 960 }
                ?.maxByOrNull { it.width * it.height }
                ?.let { parameters.setPreviewSize(it.width, it.height) }
            parameters.supportedPictureSizes
                ?.filter { it.width <= 1600 && it.height <= 1200 }
                ?.maxByOrNull { it.width * it.height }
                ?.let { parameters.setPictureSize(it.width, it.height) }
            opened.parameters = parameters
            opened.setPreviewDisplay(holder)
            opened.startPreview()
            onReady()
            if (photoRequested) captureOnce()
        } catch (e: Exception) {
            releaseCamera()
            onUnavailable("המצלמה אינה זמינה: ${e.message ?: "שגיאה"}")
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        try {
            camera?.stopPreview()
            camera?.setPreviewDisplay(holder)
            camera?.startPreview()
        } catch (_: Exception) {
            onUnavailable("תצוגת המצלמה הופסקה")
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseCamera()
    }

    fun captureOnce() {
        photoRequested = true
        val current = camera ?: return
        if (photoTaken) return
        try {
            current.takePicture(null, null) { data, activeCamera ->
                photoTaken = true
                if (data != null && data.isNotEmpty()) onPhoto(data)
            else onUnavailable("המצלמה לא החזירה תמונה")
            try { activeCamera.stopPreview() } catch (_: Exception) { }
            try { activeCamera.release() } catch (_: Exception) { }
            if (camera === activeCamera) camera = null
            }
        } catch (e: Exception) {
            onUnavailable("לא ניתן לצלם תמונת ניסיון: ${e.message ?: "שגיאה"}")
        }
    }

    private fun findFrontCamera(): Int {
        for (id in 0 until Camera.getNumberOfCameras()) {
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(id, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return id
        }
        return -1
    }

    private fun releaseCamera() {
        try { camera?.stopPreview() } catch (_: Exception) { }
        try { camera?.release() } catch (_: Exception) { }
        camera = null
    }
}
