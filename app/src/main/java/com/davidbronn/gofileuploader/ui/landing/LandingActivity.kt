package com.davidbronn.gofileuploader.ui.landing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.provider.MediaStore
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.databinding.DataBindingUtil
import com.davidbronn.gofileuploader.R
import com.davidbronn.gofileuploader.databinding.ActivityLandingBinding
import com.davidbronn.gofileuploader.ui.base.ParentActivity
import com.davidbronn.gofileuploader.utils.*
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.*

private const val PERMISSIONS_CAMERA = 100
private const val REQUEST_TAKE_PHOTO = 1000
private const val REQUEST_GALLERY_PHOTO = 1001
private const val REQUEST_DOCUMENT_FILE = 1002

class LandingActivity : ParentActivity() {

    private var imgPath = ""
    private var filePath = ""
    private var fileType = 0
    private var fileURI: Uri? = null
    private var uploadFile: File? = null
    private var alertDialog: AlertDialog? = null
    private lateinit var binding: ActivityLandingBinding
    private val permissions = arrayOf(Manifest.permission.CAMERA)
    private val storageRef by lazy { Firebase.storage.reference }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_landing)

        createAlertDialog()

        binding.btnCamera.setOnClickListener {
            if (isPermissionsAllowed(permissions, true, PERMISSIONS_CAMERA)) {
                dispatchTakePictureIntent()
            }
        }

        binding.btnGallery.setOnClickListener {
            dispatchPickImageIntent()
        }

        binding.btnFolder.setOnClickListener {
            openDocumentPicker()
        }

        binding.btnUpload.setOnClickListener {
            if (uploadFile != null && uploadFile?.exists()!!) {
                alertDialog?.show()
                performFileUploading()
            } else {
                showToast("Please select file first")
            }
        }
    }

    private fun performFileUploading() {
        val fileReference: StorageReference?
        fileReference = if (fileType == 0) {
            storageRef.child("images/${uploadFile?.name}")
        } else {
            storageRef.child("files/${uploadFile?.name}")
        }
        val uploadTask = fileReference.putFile(Uri.fromFile(uploadFile))
        uploadTask.addOnCompleteListener { task ->
            uploadFile = null
            if (alertDialog?.isShowing!!) {
                alertDialog?.hide()
            }
            if (task.isComplete) {
                showToast("Upload Done")
            }
            task.exception?.let {
                Log.e("Log", "", it)
                showToast("Upload Exception")
            }
        }.addOnCanceledListener {
            uploadFile = null
            if (alertDialog?.isShowing!!) {
                alertDialog?.hide()
            }
            showToast("Upload Cancelled")
        }
    }

    private fun createAlertDialog() {
        alertDialog = AlertDialog.Builder(this)
            .setMessage("Uploading!")
            .setCancelable(false)
            .create()
    }

    // region [CAMERA]
    private fun createImageFile(): Uri {
        getFile(currentMilli(EXT_JPG), IMAGES_FOLDER)?.run {
            createNewFile()
            fileURI = getUriForFile(this)
            imgPath = absolutePath
        }
        return fileURI!!
    }

    private fun dispatchTakePictureIntent() {
        fileType = 0
        createImageFile()
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
            takePictureIntent.resolveActivity(packageManager)?.also {
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, fileURI)
                startActivityForResult(takePictureIntent, REQUEST_TAKE_PHOTO)
            }
        }
    }
    // endregion

    // region [GALLERY]
    private fun dispatchPickImageIntent() {
        startActivityForResult(getPickImageIntent(), REQUEST_GALLERY_PHOTO)
    }

    private fun getPickImageIntent(): Intent? {
        fileType = 0
        var chooserIntent: Intent? = null
        var intentList: MutableList<Intent> = ArrayList()
        val pickIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intentList = addIntentsToList(this, intentList, pickIntent)
        if (intentList.size > 0) {
            chooserIntent = Intent.createChooser(
                intentList.removeAt(intentList.size - 1),
                getString(R.string.select_capture_image)
            )
            chooserIntent!!.putExtra(
                Intent.EXTRA_INITIAL_INTENTS,
                intentList.toTypedArray<Parcelable>()
            )
        }
        return chooserIntent
    }

    private fun addIntentsToList(context: Context, list: MutableList<Intent>, intent: Intent):
            MutableList<Intent> {
        val resInfo = context.packageManager.queryIntentActivities(intent, 0)
        for (resolveInfo in resInfo) {
            val packageName = resolveInfo.activityInfo.packageName
            val targetedIntent = Intent(intent)
            targetedIntent.setPackage(packageName)
            list.add(targetedIntent)
        }
        return list
    }
    // endregion

    // region [DOCUMENT]
    private fun openDocumentPicker() {
        fileType = 1
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        startActivityForResult(intent, REQUEST_DOCUMENT_FILE)
    }
    // endregion

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSIONS_CAMERA -> {
                if (isAllPermissionsGranted(grantResults)) {
                    dispatchTakePictureIntent()
                } else {
                    showToast(getString(R.string.permission_not_granted))
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (data?.data != null) {
            fileURI = data.data
        }
        when (requestCode) {
            REQUEST_TAKE_PHOTO, REQUEST_GALLERY_PHOTO -> {
                handlePhotoUri(fileURI)
            }
            REQUEST_DOCUMENT_FILE -> {
                handleDocumentUri(fileURI)
            }
        }
    }

    private fun handlePhotoUri(photoURI: Uri?) {
        photoURI?.let { uri ->
            CompressionHelper.create(this) {
                quality { 75 }
                compressFormat { Bitmap.CompressFormat.JPEG }
                destinationDirectoryPath {
                    getFile(
                        currentMilli(EXT_JPG),
                        IMAGES_FOLDER
                    )!!.absolutePath
                }
            }.getAsFile(this, uri) {
                imgPath.delete()
                uploadFile = it
                filePath = it.absolutePath
            }
        }
    }

    private fun handleDocumentUri(fileURI: Uri?) {
        fileURI?.let { uri ->
            launch(Dispatchers.Main) {
                copyDocument(uri) {
                    uploadFile = it
                    filePath = it.absolutePath
                }
            }
        }
    }
}
