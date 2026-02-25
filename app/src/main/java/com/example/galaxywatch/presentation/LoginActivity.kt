package com.example.galaxywatch.presentation

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.galaxywatch.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.UUID

class LoginActivity : ComponentActivity() {

    private val db = Firebase.firestore
    private lateinit var auth: FirebaseAuth
    private var pairingListener: ListenerRegistration? = null

    // Generate a unique 6-character code for this watch session
    private val watchPairingId = "WATCH_" + UUID.randomUUID().toString().substring(0, 6).uppercase()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = Firebase.auth

        // 1. Check if we are already logged in and paired
        val sharedPrefs = getSharedPreferences("ElderCarePrefs", Context.MODE_PRIVATE)
        val savedPatientId = sharedPrefs.getString("PATIENT_ID", null)

        if (auth.currentUser != null && savedPatientId != null) {
            // Already authenticated and paired! Go straight to the Health Dashboard
            goToMainActivity()
            return
        }

        // 2. Not logged in. Show the layout.
        setContentView(R.layout.activity_login)
        val statusTextView = findViewById<TextView>(R.id.login_status)
        statusTextView.text = "Connecting to secure server..."

        // 3. Authenticate the watch silently in the background
        auth.signInAnonymously()
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    Log.d("Auth", "Anonymous Auth Success")
                    // Now that we are secure, generate the QR and wait for the phone
                    showQrCodeAndListen()
                } else {
                    Log.w("Auth", "Anonymous Auth Failed", task.exception)
                    statusTextView.text = "Network Error. Please restart."
                    Toast.makeText(baseContext, "Authentication failed.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun showQrCodeAndListen() {
        val qrCodeImageView = findViewById<ImageView>(R.id.qr_code_image)
        val statusTextView = findViewById<TextView>(R.id.login_status)

        // Draw the QR Code
        val qrBitmap = generateQRCode(watchPairingId)
        if (qrBitmap != null) {
            qrCodeImageView.setImageBitmap(qrBitmap)
            statusTextView.text = "Code: $watchPairingId\nWaiting for caregiver app..."
        }

        // Register this watch in Firebase
        val pairingRef = db.collection("pairing_requests").document(watchPairingId)

        val requestData = hashMapOf(
            "status" to "waiting",
            "timestamp" to System.currentTimeMillis()
        )
        pairingRef.set(requestData)

        // Listen for the phone to scan and update this document
        pairingListener = pairingRef.addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.w("Pairing", "Listen failed.", e)
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                val status = snapshot.getString("status")

                // If the Mobile App changes the status to "paired", we let the user in!
                if (status == "paired") {
                    // This is the real Firebase Auth UID from the Phone App!
                    val assignedPatientId = snapshot.getString("patientId")

                    if (assignedPatientId != null) {
                        // Save the Patient UID permanently on the watch
                        val sharedPrefs = getSharedPreferences("ElderCarePrefs", Context.MODE_PRIVATE)
                        sharedPrefs.edit().putString("PATIENT_ID", assignedPatientId).apply()

                        // Cleanup the temporary database request and go to the dashboard
                        pairingRef.delete()
                        goToMainActivity()
                    }
                }
            }
        }
    }

    private fun goToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish() // Closes the login screen so the user can't press "back" to it
    }

    override fun onDestroy() {
        super.onDestroy()
        pairingListener?.remove()
    }

    // Function to draw the QR Code
    private fun generateQRCode(content: String): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}