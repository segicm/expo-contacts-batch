package expo.modules.contactsbatch

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

// The contacts provider caps a batch at 500 operations; stay well under it.
private const val MAX_OPS_PER_BATCH = 400

/**
 * Batched address book writes: many contacts per
 * applyBatch instead of one per call. A batch is all-or-nothing, so when one
 * fails it is retried one contact at a time and only the bad record reports
 * an error.
 */
class ExpoContactsBatchModule : Module() {
  private val resolver: ContentResolver
    get() = appContext.reactContext?.contentResolver ?: throw Exceptions.ReactContextLost()

  override fun definition() = ModuleDefinition {
    Name("ExpoContactsBatch")

    AsyncFunction("addContacts") { inputs: List<Map<String, Any?>> ->
      addAll(inputs)
    }

    AsyncFunction("updateContacts") { inputs: List<Map<String, Any?>> ->
      // An in-place update merges data rows (the old numbers would linger),
      // so replace the contact: remove it, then insert the new version. Skip
      // the insert when the removal failed, or the contact would be duplicated.
      val removed = removeAll(inputs.map { it["id"] as? String ?: "" })
      val results = MutableList(inputs.size) { removed[it] }
      val toAdd = inputs.indices.filter { removed[it]["error"] == null }
      val added = addAll(toAdd.map { inputs[it] })
      toAdd.forEachIndexed { offset, index -> results[index] = added[offset] }
      results
    }

    AsyncFunction("removeContacts") { ids: List<String> ->
      removeAll(ids)
    }
  }

  private fun addAll(inputs: List<Map<String, Any?>>): List<Map<String, Any?>> {
    val results = ArrayList<Map<String, Any?>>(inputs.size)
    var chunk = ArrayList<Map<String, Any?>>()
    var chunkOps = 0
    for (input in inputs) {
      val size = insertOps(input, 0).size
      if (chunk.isNotEmpty() && chunkOps + size > MAX_OPS_PER_BATCH) {
        results.addAll(addChunk(chunk))
        chunk = ArrayList()
        chunkOps = 0
      }
      chunk.add(input)
      chunkOps += size
    }
    if (chunk.isNotEmpty()) results.addAll(addChunk(chunk))
    return results
  }

  private fun addChunk(chunk: List<Map<String, Any?>>): List<Map<String, Any?>> {
    val ops = ArrayList<ContentProviderOperation>()
    val starts = chunk.map { input ->
      val start = ops.size
      ops.addAll(insertOps(input, start))
      start
    }
    val rawIds: List<Long?> = try {
      val applied = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
      starts.map { applied[it].uri?.let(ContentUris::parseId) }
    } catch (e: Exception) {
      chunk.map { input ->
        try {
          resolver.applyBatch(ContactsContract.AUTHORITY, insertOps(input, 0))
            .firstOrNull()?.uri?.let(ContentUris::parseId)
        } catch (single: Exception) {
          null
        }
      }
    }
    // Callers (and expo-contacts) identify contacts by aggregate contact id.
    val contactIds = contactIdsFor(rawIds.filterNotNull())
    return rawIds.map { rawId ->
      val contactId = rawId?.let { contactIds[it] }
      if (contactId != null) mapOf("id" to contactId.toString()) else mapOf("error" to "insert_failed")
    }
  }

  private fun insertOps(input: Map<String, Any?>, back: Int): ArrayList<ContentProviderOperation> {
    val ops = ArrayList<ContentProviderOperation>()
    ops.add(
      ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
        .withValue(RawContacts.ACCOUNT_TYPE, null)
        .withValue(RawContacts.ACCOUNT_NAME, null)
        .withYieldAllowed(true)
        .build()
    )

    val first = str(input["firstName"])
    val last = str(input["lastName"])
    if (first.isNotEmpty() || last.isNotEmpty()) {
      ops.add(
        dataOp(back, StructuredName.CONTENT_ITEM_TYPE)
          .withValue(StructuredName.GIVEN_NAME, first)
          .withValue(StructuredName.FAMILY_NAME, last)
          .build()
      )
    }

    val company = str(input["company"])
    if (company.isNotEmpty()) {
      ops.add(
        dataOp(back, Organization.CONTENT_ITEM_TYPE)
          .withValue(Organization.COMPANY, company)
          .withValue(Organization.TYPE, Organization.TYPE_WORK)
          .build()
      )
    }

    list(input["phoneNumbers"]).forEach { phone ->
      val number = str(phone["number"])
      if (number.isNotEmpty()) {
        ops.add(
          dataOp(back, Phone.CONTENT_ITEM_TYPE)
            .withValue(Phone.NUMBER, number)
            .withValue(Phone.TYPE, phoneType(phone["label"] as? String))
            .build()
        )
      }
    }

    list(input["emails"]).forEach { email ->
      val address = str(email["email"])
      if (address.isNotEmpty()) {
        ops.add(
          dataOp(back, Email.CONTENT_ITEM_TYPE)
            .withValue(Email.ADDRESS, address)
            .withValue(Email.TYPE, if (email["label"] == "home") Email.TYPE_HOME else Email.TYPE_WORK)
            .build()
        )
      }
    }
    return ops
  }

  private fun removeAll(ids: List<String>): List<Map<String, Any?>> {
    val results = MutableList<Map<String, Any?>>(ids.size) { mapOf("ok" to true) }
    val valid = ArrayList<Pair<Int, Long>>()
    ids.forEachIndexed { index, id ->
      val parsed = id.toLongOrNull()
      if (parsed == null) results[index] = mapOf("error" to "invalid_id") else valid.add(index to parsed)
    }
    valid.chunked(MAX_OPS_PER_BATCH).forEach { chunk ->
      // Deleting an id that is already gone affects 0 rows: counts as removed.
      val ops = ArrayList(chunk.map { (_, id) -> deleteOp(id) })
      try {
        resolver.applyBatch(ContactsContract.AUTHORITY, ops)
      } catch (e: Exception) {
        chunk.forEach { (index, id) ->
          try {
            resolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(deleteOp(id)))
          } catch (single: Exception) {
            results[index] = mapOf("error" to (single.message ?: "remove_failed"))
          }
        }
      }
    }
    return results
  }

  private fun contactIdsFor(rawIds: List<Long>): Map<Long, Long> {
    val byRawId = HashMap<Long, Long>()
    rawIds.chunked(500).forEach { part ->
      resolver.query(
        RawContacts.CONTENT_URI,
        arrayOf(RawContacts._ID, RawContacts.CONTACT_ID),
        "${RawContacts._ID} IN (${part.joinToString(",")})",
        null,
        null
      )?.use { cursor ->
        while (cursor.moveToNext()) byRawId[cursor.getLong(0)] = cursor.getLong(1)
      }
    }
    return byRawId
  }

  private fun deleteOp(contactId: Long) =
    ContentProviderOperation.newDelete(ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId)).build()

  private fun dataOp(back: Int, mimeType: String) =
    ContentProviderOperation.newInsert(Data.CONTENT_URI)
      .withValueBackReference(Data.RAW_CONTACT_ID, back)
      .withValue(Data.MIMETYPE, mimeType)

  private fun phoneType(label: String?) = when (label) {
    "home" -> Phone.TYPE_HOME
    "work" -> Phone.TYPE_WORK
    "main" -> Phone.TYPE_MAIN
    "fax" -> Phone.TYPE_FAX_WORK
    "pager" -> Phone.TYPE_PAGER
    "other" -> Phone.TYPE_OTHER
    else -> Phone.TYPE_MOBILE
  }

  private fun str(value: Any?) = (value as? String)?.trim() ?: ""

  @Suppress("UNCHECKED_CAST")
  private fun list(value: Any?) = (value as? List<Map<String, Any?>>) ?: emptyList()
}
