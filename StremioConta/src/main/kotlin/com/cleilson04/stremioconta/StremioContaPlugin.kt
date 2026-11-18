package com.cleilson04.stremioconta

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StremioContaPlugin : Plugin() {
    override fun load(context: Context) {
        Prefs.init(context)
        registerMainAPI(StremioContaProvider())

        openSettings = { ctx ->
            val email = EditText(ctx).apply { hint = "E-mail do Stremio" }
            val senha = EditText(ctx).apply {
                hint = "Senha"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 24, 48, 0)
                addView(email)
                addView(senha)
            }
            AlertDialog.Builder(ctx)
                .setTitle("Login Stremio")
                .setView(box)
                .setPositiveButton("Entrar") { _, _ ->
                    Prefs.pendingEmail = email.text.toString().trim()
                    Prefs.pendingPassword = senha.text.toString()
                    Prefs.authKey = null
                }
                .setNeutralButton("Sair") { _, _ -> Prefs.clear() }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }
}

object Prefs {
    private var ctx: Context? = null
    private val sp get() = ctx!!.getSharedPreferences("stremio_conta", Context.MODE_PRIVATE)

    fun init(c: Context) {
        ctx = c.applicationContext
    }

    var authKey: String?
        get() = sp.getString("authKey", null)
        set(v) = sp.edit().putString("authKey", v).apply()

    var pendingEmail: String? = null
    var pendingPassword: String? = null

    fun clear() {
        pendingEmail = null
        pendingPassword = null
        sp.edit().clear().apply()
    }
}
