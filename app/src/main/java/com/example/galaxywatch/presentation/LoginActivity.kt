package com.example.galaxywatch.presentation

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.galaxywatch.R

class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val usernameEditText = findViewById<EditText>(R.id.username)
        val passwordEditText = findViewById<EditText>(R.id.password)
        val signUpTextView = findViewById<TextView>(R.id.signup_button)

        // 1. Setup the Clickable Span for "Sign Up"
        // 1. Define the text with a newline
        val fullText = "Don't have an account?\nSign Up"
        val spannableString = SpannableString(fullText)

// 2. Create the click logic
        val clickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                val intent = Intent(this@LoginActivity, SignupActivity::class.java)
                startActivity(intent)
            }

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isUnderlineText = true
                ds.color = Color.CYAN
                ds.isFakeBoldText = true // Making it bold helps visibility on a watch
            }
        }

// 3. Set the span on "Sign Up"
// "Don't have an account?\n" is 23 characters long.
// So "Sign Up" now starts at index 23.
        spannableString.setSpan(clickableSpan, 23, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

// 4. Apply to the TextView
        signUpTextView.text = spannableString
        signUpTextView.movementMethod = LinkMovementMethod.getInstance()
        signUpTextView.highlightColor = Color.TRANSPARENT

        // Login Button Logic
        findViewById<Button>(R.id.login_button).setOnClickListener {
            val username = usernameEditText.text.toString()
            val password = passwordEditText.text.toString()

            Toast.makeText(this, "Logging in...", Toast.LENGTH_SHORT).show()

            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}