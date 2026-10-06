package com.wanderwildwood.aikotoba.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.wanderwildwood.aikotoba.R
import com.wanderwildwood.aikotoba.vault.Item
import com.wanderwildwood.aikotoba.vault.Session
import com.wanderwildwood.aikotoba.vault.Vault

/**
 * Filling sign-ins in other apps.
 *
 * While the vault is open, the entries that belong to the asking app or site are offered by
 * name, and one more line opens a search of the whole vault. While it is locked there is one
 * line, which unlocks it first. Nothing is offered on this app's own screens, and nothing is
 * ever saved from what is typed elsewhere.
 *
 * The system starts this service when a sign-in screen asks, even after the app was swiped
 * away. A background manager that force-stops apps (DuraSpeed on the Kompakt) can stop that
 * from happening at all; the README and Settings say how to let the app be.
 */
class FillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess(null)
            return
        }
        val fields = Fields.from(structure)
        if (!fields.fillable || fields.packageName == packageName) {
            callback.onSuccess(null)
            return
        }
        val response = FillResponse.Builder()
        val open = Session.open
        if (open != null) {
            open.items
                .filter { Match.matches(it, fields.packageName, fields.webDomain) }
                .take(MAX_OFFERED)
                .forEach { item -> response.addDataset(dataset(this, item, Vault.password(open.db, item.uuid), fields)) }
        }
        response.addDataset(searchDataset(this, fields, locked = open == null))
        callback.onSuccess(response.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // No SaveInfo is ever offered, so this is not called; answered all the same.
        callback.onSuccess()
    }

    companion object {
        private const val MAX_OFFERED = 5

        fun row(context: Context, text: String): RemoteViews =
            RemoteViews(context.packageName, R.layout.fill_row).apply { setTextViewText(R.id.fill_text, text) }

        /** One entry's user name and password, set into the screen's fields. */
        fun dataset(context: Context, item: Item, password: String, fields: Fields): Dataset {
            val label = if (item.username.isBlank() || item.username == item.label) item.label else "${item.label} · ${item.username}"
            val presentation = row(context, label)
            val builder = Dataset.Builder()
            fields.username?.let { builder.setValue(it, AutofillValue.forText(item.username), presentation) }
            fields.password?.let { builder.setValue(it, AutofillValue.forText(password), presentation) }
            return builder.build()
        }

        /** "Search the vault", or "Unlock" first: opens [FillActivity] and fills what is chosen there. */
        private fun searchDataset(context: Context, fields: Fields, locked: Boolean): Dataset {
            val presentation = row(context, context.getString(if (locked) R.string.fill_unlock else R.string.fill_search))
            val intent = Intent(context, FillActivity::class.java)
            // Mutable: the system adds the screen's structure to it before starting the activity.
            val pending = PendingIntent.getActivity(
                context,
                REQUEST,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
            )
            val builder = Dataset.Builder()
            for (id in fields.ids) builder.setValue(id, null, presentation)
            builder.setAuthentication(pending.intentSender)
            return builder.build()
        }

        private const val REQUEST = 1
    }
}
