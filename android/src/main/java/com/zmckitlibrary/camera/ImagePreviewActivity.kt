package com.zmckitlibrary.camera

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.zmckitlibrary.R
import com.zmckitlibrary.camera.Constants.EXTRA_IMAGE_URI

import java.io.File
import java.io.InputStream
import java.io.OutputStream

class ImagePreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_preview)

        val imagePath = intent.getStringExtra(EXTRA_IMAGE_URI)
        val imageView = findViewById<ImageView>(R.id.preview_image_view)
        val downloadButton = findViewById<ImageButton>(R.id.download_button)
        val shareButton = findViewById<ImageButton>(R.id.share_button)
        val closeButton = findViewById<ImageButton>(R.id.close_button)

        if (imagePath != null) {
            val imageUri = Uri.fromFile(File(imagePath))
            imageView.setImageURI(imageUri)

            downloadButton.setOnClickListener {
                downloadImageToGallery(imageUri)
            }

            shareButton.setOnClickListener {
                shareImage(imageUri)
            }
        }

        closeButton.setOnClickListener {
            finish()
        }
    }

    private fun getDefaultProviderAuthority(): String {
        return "${this.packageName}.provider"
    }

    private fun shareImage(imageUri: Uri) {
        imageUri.path?.let { imagePath ->
            // Get the content URI using FileProvider
            val contentUri: Uri = FileProvider.getUriForFile(
                this,
                getDefaultProviderAuthority(),
                File(imagePath)
            )

            // Create the share intent
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // Start the share intent
            startActivity(Intent.createChooser(shareIntent, "Share Image"))
        }
    }

    private fun downloadImageToGallery(imageUri: Uri) {
        try {
            // Get the content resolver and prepare the values to insert
            val contentResolver = applicationContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "captured_image.jpg") // Image file name
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg") // MIME type for JPEG images
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ZMC")
            }

            // Insert the image into the MediaStore
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Failed to insert image into MediaStore.")

            // Open an output stream to write to the new image location
            val outputStream: OutputStream? = contentResolver.openOutputStream(uri)
            val inputStream: InputStream? = contentResolver.openInputStream(imageUri)

            inputStream?.let {
                inputStream.copyTo(outputStream!!, 1024)
                outputStream.flush()
                outputStream.close()

                // Notify the user
                Toast.makeText(applicationContext, "Fotoğraf kaydedildi!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            // Handle any errors (e.g., permission issues, file write errors)
            e.printStackTrace()
            Toast.makeText(applicationContext, "Resim kaydedilemedi!", Toast.LENGTH_SHORT).show()
        }
    }
}