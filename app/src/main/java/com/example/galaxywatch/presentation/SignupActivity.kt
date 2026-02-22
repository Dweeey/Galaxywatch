package com.example.galaxywatch.presentation

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.galaxywatch.R

class SignupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        val emailEditText = findViewById<EditText>(R.id.signup_email)
        val passwordEditText = findViewById<EditText>(R.id.signup_password)
        val confirmPasswordEditText = findViewById<EditText>(R.id.signup_confirm_password)

        findViewById<Button>(R.id.signup_submit_button).setOnClickListener {
            val email = emailEditText.text.toString()
            val password = passwordEditText.text.toString()
            val confirmPassword = confirmPasswordEditText.text.toString()

            if (password == confirmPassword) {
                // TODO: Add user registration logic here
                Toast.makeText(this, "Registration successful", Toast.LENGTH_SHORT).show()
                finish() // Go back to login screen
            } else {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
