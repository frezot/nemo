package com.nemo.networkconditioner.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BulletSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.nemo.networkconditioner.R

class AboutActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.about_activity)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        setTitle(R.string.about)
        displayBackAction()

        bindSpannableText(R.id.about_license, buildLicenseText())
        bindSpannableText(R.id.about_credits, buildCreditsText())
        bindSpannableText(R.id.about_dependencies, buildDependenciesText())
    }

    private fun bindSpannableText(viewId: Int, text: CharSequence) {
        val textView = findViewById<TextView>(viewId)
        textView.text = text
        textView.linksClickable = true
        textView.movementMethod = LinkMovementMethod.getInstance()
        textView.setLinkTextColor(ContextCompat.getColor(this, R.color.colorAccent))
    }

    private fun buildLicenseText(): CharSequence {
        val builder = SpannableStringBuilder()
        builder.append(
            "NEMO is distributed in the hope that it will be useful, but WITHOUT " +
                "ANY WARRANTY; without even the implied warranty of MERCHANTABILITY " +
                "or FITNESS FOR A PARTICULAR PURPOSE. See the ",
        )
        appendLink(
            builder,
            "GNU General Public License or later",
            "https://www.gnu.org/licenses/gpl-3.0-standalone.html",
        )
        builder.append(" for more details.")
        return builder
    }

    private fun buildCreditsText(): CharSequence {
        val builder = SpannableStringBuilder()
        appendBullet(builder)
        builder.append("Based on ideas explored in ")
        appendLink(builder, "PCAPdroid", "https://github.com/emanuele-f/PCAPdroid")
        builder.append(" by Emanuele Faranda\n")
        appendBullet(builder)
        builder.append("Inspired by ")
        appendLink(
            builder,
            "Network Link Conditioner",
            "https://nshipster.com/network-link-conditioner/",
        )
        builder.append(" by Apple")
        return builder
    }

    private fun buildDependenciesText(): CharSequence {
        val builder = SpannableStringBuilder()
        appendBullet(builder)
        builder.append("zdtun - ")
        appendLink(builder, "LGPL-3.0", "https://github.com/emanuele-f/zdtun/blob/master/COPYING")
        builder.append("\n")
        appendBullet(builder)
        builder.append("CustomActivityOnCrash - ")
        appendLink(
            builder,
            "Apache-2.0",
            "https://github.com/Ereza/CustomActivityOnCrash/blob/master/LICENSE",
        )
        return builder
    }

    private fun appendLink(builder: SpannableStringBuilder, text: String, url: String) {
        val start = builder.length
        builder.append(text)
        val end = builder.length
        builder.setSpan(BrowserClickableSpan(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.colorAccent)),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun appendBullet(builder: SpannableStringBuilder) {
        val start = builder.length
        builder.append("  ")
        builder.setSpan(BulletSpan(18), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private inner class BrowserClickableSpan(private val url: String) : ClickableSpan() {
        override fun onClick(widget: View) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        override fun updateDrawState(ds: TextPaint) {
            super.updateDrawState(ds)
            ds.color = ContextCompat.getColor(this@AboutActivity, R.color.colorAccent)
            ds.isUnderlineText = false
        }
    }
}
