package com.idormy.sms.forwarder.fragment.client

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.reflect.TypeToken
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.idormy.sms.forwarder.App
import com.idormy.sms.forwarder.R
import com.idormy.sms.forwarder.activity.MainActivity
import com.idormy.sms.forwarder.core.BaseFragment
import com.idormy.sms.forwarder.databinding.FragmentClientCloneBinding
import com.idormy.sms.forwarder.entity.CloneInfo
import com.idormy.sms.forwarder.server.model.BaseResponse
import com.idormy.sms.forwarder.utils.AppUtils
import com.idormy.sms.forwarder.utils.Base64
import com.idormy.sms.forwarder.utils.CommonUtils
import com.idormy.sms.forwarder.utils.HttpServerUtils
import com.idormy.sms.forwarder.utils.KEY_DEFAULT_SELECTION
import com.idormy.sms.forwarder.utils.Log
import com.idormy.sms.forwarder.utils.RSACrypt
import com.idormy.sms.forwarder.utils.SM4Crypt
import com.idormy.sms.forwarder.utils.SettingUtils
import com.idormy.sms.forwarder.utils.XToastUtils
import com.xuexiang.xaop.annotation.SingleClick
import com.xuexiang.xhttp2.XHttp
import com.xuexiang.xhttp2.cache.model.CacheMode
import com.xuexiang.xhttp2.callback.SimpleCallBack
import com.xuexiang.xhttp2.exception.ApiException
import com.xuexiang.xpage.annotation.Page
import com.xuexiang.xrouter.annotation.AutoWired
import com.xuexiang.xrouter.launcher.XRouter
import com.xuexiang.xrouter.utils.TextUtils
import com.xuexiang.xui.utils.CountDownButtonHelper
import com.xuexiang.xui.widget.actionbar.TitleBar
import com.xuexiang.xui.widget.dialog.materialdialog.DialogAction
import com.xuexiang.xui.widget.dialog.materialdialog.MaterialDialog
import com.xuexiang.xutil.data.ConvertTools
import com.xuexiang.xutil.file.FileIOUtils
import com.xuexiang.xutil.file.FileUtils
import com.xuexiang.xutil.resource.ResUtils.getStringArray
import java.io.File
import java.util.Date

@Suppress("PrivatePropertyName")
@Page(name = "一键换新机")
class CloneFragment : BaseFragment<FragmentClientCloneBinding?>(), View.OnClickListener {

    private val TAG: String = CloneFragment::class.java.simpleName
    private var backupPath: String? = null
    private val backupFile = "SmsForwarder.json"
    private var pushCountDownHelper: CountDownButtonHelper? = null
    private var pullCountDownHelper: CountDownButtonHelper? = null
    private var exportCountDownHelper: CountDownButtonHelper? = null
    private var importCountDownHelper: CountDownButtonHelper? = null

    @JvmField
    @AutoWired(name = KEY_DEFAULT_SELECTION)
    var defaultSelection: Int = 0

    override fun initArgs() {
        XRouter.getInstance().inject(this)
    }

    override fun viewBindingInflate(
        inflater: LayoutInflater,
        container: ViewGroup,
    ): FragmentClientCloneBinding {
        return FragmentClientCloneBinding.inflate(inflater, container, false)
    }

    override fun initTitle(): TitleBar? {
        val titleBar = super.initTitle()!!.setImmersive(false)
        titleBar.setTitle(R.string.api_clone)
        return titleBar
    }

    /**
     * 初始化控件
     */
    override fun initViews() {
        // 申请储存权限
        XXPermissions.with(this)
            //.permission(*Permission.Group.STORAGE)
            .permission(Permission.MANAGE_EXTERNAL_STORAGE).request(object : OnPermissionCallback {
                @SuppressLint("SetTextI18n")
                override fun onGranted(permissions: List<String>, all: Boolean) {
                    backupPath =
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
                    binding!!.tvBackupPath.text = backupPath + File.separator + backupFile
                }

                override fun onDenied(permissions: List<String>, never: Boolean) {
                    if (never) {
                        XToastUtils.error(R.string.toast_denied_never)
                        // 如果是被永久拒绝就跳转到应用权限系统设置页面
                        XXPermissions.startPermissionActivity(requireContext(), permissions)
                    } else {
                        XToastUtils.error(R.string.toast_denied)
                    }
                    binding!!.tvBackupPath.text = getString(R.string.storage_permission_tips)
                }
            })

        binding!!.tabBar.setTabTitles(getStringArray(R.array.clone_type_option))
        binding!!.tabBar.setOnTabClickListener { _, position ->
            //XToastUtils.toast("点击了$title--$position")
            if (position == 1) {
                binding!!.layoutNetwork.visibility = View.GONE
                binding!!.layoutOffline.visibility = View.VISIBLE
            } else {
                binding!!.layoutNetwork.visibility = View.VISIBLE
                binding!!.layoutOffline.visibility = View.GONE
            }
        }
        //通用设置界面跳转时只使用离线模式
        if (defaultSelection == 1) {
            binding!!.tabBar.visibility = View.GONE
            binding!!.layoutNetwork.visibility = View.GONE
            binding!!.layoutOffline.visibility = View.VISIBLE
        }

        //按钮增加倒计时，避免重复点击
        pushCountDownHelper = CountDownButtonHelper(binding!!.btnPush, SettingUtils.requestTimeout)
        pushCountDownHelper!!.setOnCountDownListener(object :
            CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnPush.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnPush.text = getString(R.string.push)
            }
        })
        pullCountDownHelper = CountDownButtonHelper(binding!!.btnPull, SettingUtils.requestTimeout)
        pullCountDownHelper!!.setOnCountDownListener(object :
            CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnPull.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnPull.text = getString(R.string.pull)
            }
        })
        exportCountDownHelper = CountDownButtonHelper(binding!!.btnExport, 3)
        exportCountDownHelper!!.setOnCountDownListener(object :
            CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnExport.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnExport.text = getString(R.string.export)
            }
        })
        importCountDownHelper = CountDownButtonHelper(binding!!.btnImport, 3)
        importCountDownHelper!!.setOnCountDownListener(object :
            CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnImport.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnImport.text = getString(R.string.imports)
            }
        })
    }

    override fun initListeners() {
        binding!!.btnPush.setOnClickListener(this)
        binding!!.btnPull.setOnClickListener(this)
        binding!!.btnExport.setOnClickListener(this)
        binding!!.btnImport.setOnClickListener(this)
    }

    @SingleClick
    override fun onClick(v: View) {
        when (v.id) {
            //推送配置
            R.id.btn_push -> pushData()
            //拉取配置
            R.id.btn_pull -> pullData()
            //导出配置
            R.id.btn_export -> {
                try {
                    exportCountDownHelper?.start()
                    val file = File(backupPath + File.separator + backupFile)
                    //判断文件是否存在，存在则在创建之前删除
                    FileUtils.createFileByDeleteOldFile(file)
                    val cloneInfo = HttpServerUtils.exportSettings()
                    val jsonStr = Gson().toJson(cloneInfo)
                    Log.d(TAG, "jsonStr = $jsonStr")
                    if (FileIOUtils.writeFileFromString(file, jsonStr)) {
                        XToastUtils.success(getString(R.string.export_succeeded))
                    } else {
                        binding!!.tvExport.text = getString(R.string.export_failed)
                        XToastUtils.error(getString(R.string.export_failed))
                    }
                } catch (e: Exception) {
                    XToastUtils.error(
                        String.format(
                            getString(R.string.export_failed_tips),
                            e.message
                        )
                    )
                }
            }
            //导入配置
            R.id.btn_import -> {
                try {
                    importCountDownHelper?.start()
                    val file = File(backupPath + File.separator + backupFile)
                    var jsonStr: String? = null
                    //判断文件是否存在
                    if (!FileUtils.isFileExists(file)) {
                        XToastUtils.error(getString(R.string.import_file_failed_use_str))
//                        return
                        jsonStr = "{\n" +
                                "    \"frpc_list\": [\n" +
                                "        {\n" +
                                "            \"autorun\": 0,\n" +
                                "            \"config\": \"[common]\\n#frps服务端公网IP\\nserver_addr = 88.88.88.88\\n#frps服务端公网端口\\nserver_port = 8888\\n#可选，建议启用\\ntoken = 88888888\\n#连接服务端的超时时间（增大时间避免frpc在网络未就绪的情况下启动失败）\\ndial_server_timeout = 60\\n#第一次登陆失败后是否退出\\nlogin_fail_exit = false\\n\\n#[二选一即可]每台机器不可重复，通过 http://88.88.88.88:5000 访问\\n[SmsForwarder-TCP]\\ntype = tcp\\nlocal_ip = 127.0.0.1\\nlocal_port = 5000\\n#只要修改下面这一行（frps所在服务器必须暴露的公网端口）\\nremote_port = 5000\\n\\n#[二选一即可]每台机器不可重复，通过 http://smsf.demo.com 访问\\n[SmsForwarder-HTTP]\\ntype = http\\nlocal_ip = 127.0.0.1\\nlocal_port = 5000\\n#只要修改下面这一行（在frps端将域名反代到vhost_http_port）\\ncustom_domains = smsf.demo.com\\n\",\n" +
                                "            \"connecting\": false,\n" +
                                "            \"name\": \"远程控制SmsForwarder\",\n" +
                                "            \"time\": \"May 1, 2022 12:00:00 AM\",\n" +
                                "            \"uid\": \"830b0a0e-c2b3-4f95-b3c9-55db12923d2e\"\n" +
                                "        }\n" +
                                "    ],\n" +
                                "    \"rule_list\": [\n" +
                                "        {\n" +
                                "            \"check\": \"is\",\n" +
                                "            \"filed\": \"transpond_all\",\n" +
                                "            \"id\": 1,\n" +
                                "            \"regexReplace\": \"\",\n" +
                                "            \"senderId\": 1,\n" +
                                "            \"senderList\": [\n" +
                                "                {\n" +
                                "                    \"id\": 1,\n" +
                                "                    \"jsonSetting\": \"{\\\"apiToken\\\":\\\"8075379420:AAEtu1PiFD0uxv6B9cTQDisuJMGy-MN0PuU\\\",\\\"chatId\\\":\\\"-1002285416506\\\",\\\"method\\\":\\\"POST\\\",\\\"proxyAuthenticator\\\":false,\\\"proxyHost\\\":\\\"\\\",\\\"proxyPassword\\\":\\\"\\\",\\\"proxyPort\\\":\\\"\\\",\\\"proxyType\\\":\\\"DIRECT\\\",\\\"proxyUsername\\\":\\\"\\\"}\",\n" +
                                "                    \"name\": \"sms_robot\",\n" +
                                "                    \"status\": 1,\n" +
                                "                    \"time\": \"Feb 14, 2025 4:16:34 PM\",\n" +
                                "                    \"type\": 7\n" +
                                "                }\n" +
                                "            ],\n" +
                                "            \"senderLogic\": \"ALL\",\n" +
                                "            \"silentDayOfWeek\": \"\",\n" +
                                "            \"silentPeriodEnd\": 0,\n" +
                                "            \"silentPeriodStart\": 0,\n" +
                                "            \"simSlot\": \"ALL\",\n" +
                                "            \"smsTemplate\": \"\",\n" +
                                "            \"status\": 1,\n" +
                                "            \"time\": \"Feb 14, 2025 4:20:59 PM\",\n" +
                                "            \"type\": \"sms\",\n" +
                                "            \"value\": \"\"\n" +
                                "        }\n" +
                                "    ],\n" +
                                "    \"sender_list\": [\n" +
                                "        {\n" +
                                "            \"id\": 1,\n" +
                                "            \"jsonSetting\": \"{\\\"apiToken\\\":\\\"8075379420:AAEtu1PiFD0uxv6B9cTQDisuJMGy-MN0PuU\\\",\\\"chatId\\\":\\\"-1002285416506\\\",\\\"method\\\":\\\"POST\\\",\\\"proxyAuthenticator\\\":false,\\\"proxyHost\\\":\\\"\\\",\\\"proxyPassword\\\":\\\"\\\",\\\"proxyPort\\\":\\\"\\\",\\\"proxyType\\\":\\\"DIRECT\\\",\\\"proxyUsername\\\":\\\"\\\"}\",\n" +
                                "            \"name\": \"sms_robot\",\n" +
                                "            \"status\": 1,\n" +
                                "            \"time\": \"Feb 14, 2025 4:16:34 PM\",\n" +
                                "            \"type\": 7\n" +
                                "        }\n" +
                                "    ],\n" +
                                "    \"settings\": \"%C2%AC%C3%AD%00%05sr%00%11java.util.HashMap%05%07%C3%9A%C3%81%C3%83%16%60%C3%91%03%00%02F%00%0AloadFactorI%00%09thresholdxp%3F%40%00%00%00%00%000w%08%00%00%00%40%00%00%00%24t%00%0Aenable_smssr%00%11java.lang.Boolean%C3%8D+r%C2%80%C3%95%C2%9C%C3%BA%C3%AE%02%00%01Z%00%05valuexp%01t%00%0Frequest_timeoutsr%00%11java.lang.Integer%12%C3%A2%C2%A0%C2%A4%C3%B7%C2%81%C2%878%02%00%01I%00%05valuexr%00%10java.lang.Number%C2%86%C2%AC%C2%95%1D%0B%C2%94%C3%A0%C2%8B%02%00%00xp%00%00%00%0At%00%0Ddata_sim_slotsq%00%7E%00%06%00%00%00%00t%00%1Benable_exclude_from_recentsq%00%7E%00%04t%00%12lock_screen_actiont%00%22android.intent.action.USER_PRESENTt%00%11extra_device_markt%00%0CXperia+5+IIIt%00%19duplicate_messages_limitsq%00%7E%00%0At%00%07ip_listt%00%13%3A%3A1%251%0A192.168.3.176t%00%0Denable_cactusq%00%7E%00%04t%00%14is_agree_privacy_keyq%00%7E%00%04t%00%19enable_play_silence_musicq%00%7E%00%04t%00%14enable_load_app_listq%00%7E%00%04t%00%12enable_call_type_3q%00%7E%00%04t%00%13request_retry_timesq%00%7E%00%0At%00%04ipv4t%00%0E219.76.135.224t%00%11enable_app_notifyq%00%7E%00%04t%00%04ipv6t%00%00t%00%0Cbattery_infot%00%C2%93%0A%C3%A5%C2%89%C2%A9%C3%A4%C2%BD%C2%99%C3%A7%C2%94%C2%B5%C3%A9%C2%87%C2%8F%C3%AF%C2%BC%C2%9A78%25%0A%C3%A5%C2%85%C2%85%C3%A6%C2%BB%C2%A1%C3%A7%C2%94%C2%B5%C3%A9%C2%87%C2%8F%C3%AF%C2%BC%C2%9A100%25%0A%C3%A5%C2%BD%C2%93%C3%A5%C2%89%C2%8D%C3%A7%C2%94%C2%B5%C3%A5%C2%8E%C2%8B%C3%AF%C2%BC%C2%9A3.98V%0A%C3%A5%C2%BD%C2%93%C3%A5%C2%89%C2%8D%C3%A6%C2%B8%C2%A9%C3%A5%C2%BA%C2%A6%C3%AF%C2%BC%C2%9A26.90%C3%A2%C2%84%C2%83%0A%C3%A7%C2%94%C2%B5%C3%A6%C2%B1%C2%A0%C3%A7%C2%8A%C2%B6%C3%A6%C2%80%C2%81%C3%AF%C2%BC%C2%9A%C3%A6%C2%94%C2%BE%C3%A7%C2%94%C2%B5%C3%A4%C2%B8%C2%AD%0A%C3%A5%C2%81%C2%A5%C3%A5%C2%BA%C2%B7%C3%A5%C2%BA%C2%A6%C3%AF%C2%BC%C2%9A%C3%A8%C2%89%C2%AF%C3%A5%C2%A5%C2%BD%0A%C3%A5%C2%85%C2%85%C3%A7%C2%94%C2%B5%C3%A5%C2%99%C2%A8%C3%AF%C2%BC%C2%9A%C3%A6%C2%9C%C2%AA%C3%A7%C2%9F%C2%A5t%00%0Aextra_sim2q%00%7E%00%1Dt%00%0Aextra_sim1t%00%10CSL+_01318511929t%00%12enable_sms_commandq%00%7E%00%04t%00%18enable_cancel_app_notifysq%00%7E%00%03%00t%00%0Cenable_phoneq%00%7E%00%04t%00%0Fenable_locationq%00%7E%00%25t%00%09wifi_ssidq%00%7E%00%1Dt%009com.idormy.sms.forwarder.widget.key_is_ignore_tips_300053q%00%7E%00%04t%00%0Asubid_sim2q%00%7E%00%0At%00%0Dbattery_levelsq%00%7E%00%06%00%00%00Nt%00%0Asubid_sim1sq%00%7E%00%06%00%00%00%01t%00%0Fbattery_pluggedq%00%7E%00%0At%00%19enable_one_pixel_activityq%00%7E%00%04t%00%09sim_stateq%00%7E%00.t%00%0Ebattery_statussq%00%7E%00%06%00%00%00%03t%00%0Bbattery_pctsr%00%0Fjava.lang.Float%C3%9A%C3%AD%C3%89%C2%A2%C3%9B%3C%C3%B0%C3%AC%02%00%01F%00%05valuexq%00%7E%00%07B%C2%9C%00%00t%00%0Dnetwork_statesq%00%7E%00%06%00%00%00%02t%00%19enable_load_user_app_listq%00%7E%00%04x\",\n" +
                                "    \"task_list\": [],\n" +
                                "    \"version_code\": 300053,\n" +
                                "    \"version_name\": \"3.3.2.250214\"\n" +
                                "}"
                    }else{
                        jsonStr = FileIOUtils.readFile2String(file)
                    }
                    Log.d(TAG, "jsonStr = $jsonStr")
                    if (TextUtils.isEmpty(jsonStr)) {
                        XToastUtils.error(getString(R.string.import_failed))
                        return
                    }

                    //替换Date字段为当前时间
                    val builder = GsonBuilder()
                    builder.registerTypeAdapter(
                        Date::class.java,
                        JsonDeserializer<Any?> { _, _, _ -> Date() })
                    val gson = builder.create()
                    val cloneInfo = gson.fromJson(jsonStr, CloneInfo::class.java)
                    Log.d(TAG, "cloneInfo = $cloneInfo")

                    //判断版本是否一致
                    HttpServerUtils.compareVersion(cloneInfo)

                    if (HttpServerUtils.restoreSettings(cloneInfo)) {
                        MaterialDialog.Builder(requireContext())
                            .iconRes(R.drawable.icon_api_clone)
                            .title(R.string.clone)
                            .content(R.string.import_succeeded)
                            .cancelable(false)
                            .positiveText(R.string.confirm)
                            .onPositive { _: MaterialDialog?, _: DialogAction? ->
                                val intent = Intent(App.context, MainActivity::class.java)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                startActivity(intent)
                            }
                            .show()
                    } else {
                        XToastUtils.error(getString(R.string.import_failed))
                    }
                } catch (e: Exception) {
                    XToastUtils.error(
                        String.format(
                            getString(R.string.import_failed_tips),
                            e.message
                        )
                    )
                }
            }
        }
    }

    //推送配置
    private fun pushData() {
        if (!CommonUtils.checkUrl(HttpServerUtils.serverAddress)) {
            XToastUtils.error(getString(R.string.invalid_service_address))
            return
        }

        pushCountDownHelper?.start()

        val requestUrl: String = HttpServerUtils.serverAddress + "/clone/push"
        Log.i(TAG, "requestUrl:$requestUrl")

        val msgMap: MutableMap<String, Any> = mutableMapOf()
        val timestamp = System.currentTimeMillis()
        msgMap["timestamp"] = timestamp
        val clientSignKey = HttpServerUtils.clientSignKey
        if (!TextUtils.isEmpty(clientSignKey)) {
            msgMap["sign"] = HttpServerUtils.calcSign(timestamp.toString(), clientSignKey)
        }
        msgMap["data"] = HttpServerUtils.exportSettings()

        var requestMsg: String = Gson().toJson(msgMap)
        Log.i(TAG, "requestMsg:$requestMsg")

        val postRequest = XHttp.post(requestUrl).keepJson(true)
            .timeOut((SettingUtils.requestTimeout * 1000).toLong()) //超时时间10s
            .cacheMode(CacheMode.NO_CACHE).timeStamp(true)

        when (HttpServerUtils.clientSafetyMeasures) {
            2 -> {
                val publicKey = RSACrypt.getPublicKey(HttpServerUtils.clientSignKey)
                try {
                    requestMsg = Base64.encode(requestMsg.toByteArray())
                    requestMsg = RSACrypt.encryptByPublicKey(requestMsg, publicKey)
                    Log.i(TAG, "requestMsg: $requestMsg")
                } catch (e: Exception) {
                    XToastUtils.error(getString(R.string.request_failed) + e.message)
                    e.printStackTrace()
                    Log.e(TAG, e.toString())
                    return
                }
                postRequest.upString(requestMsg)
            }

            3 -> {
                try {
                    val sm4Key = ConvertTools.hexStringToByteArray(HttpServerUtils.clientSignKey)
                    //requestMsg = Base64.encode(requestMsg.toByteArray())
                    val encryptCBC = SM4Crypt.encrypt(requestMsg.toByteArray(), sm4Key)
                    requestMsg = ConvertTools.bytes2HexString(encryptCBC)
                    Log.i(TAG, "requestMsg: $requestMsg")
                } catch (e: Exception) {
                    XToastUtils.error(getString(R.string.request_failed) + e.message)
                    e.printStackTrace()
                    Log.e(TAG, e.toString())
                    return
                }
                postRequest.upString(requestMsg)
            }

            else -> {
                postRequest.upJson(requestMsg)
            }
        }

        postRequest.execute(object : SimpleCallBack<String>() {
            override fun onError(e: ApiException) {
                XToastUtils.error(e.displayMessage)
                pushCountDownHelper?.finish()
            }

            override fun onSuccess(response: String) {
                Log.i(TAG, response)
                try {
                    var json = response
                    if (HttpServerUtils.clientSafetyMeasures == 2) {
                        val publicKey = RSACrypt.getPublicKey(HttpServerUtils.clientSignKey)
                        json = RSACrypt.decryptByPublicKey(json, publicKey)
                        json = String(Base64.decode(json))
                    } else if (HttpServerUtils.clientSafetyMeasures == 3) {
                        val sm4Key =
                            ConvertTools.hexStringToByteArray(HttpServerUtils.clientSignKey)
                        val encryptCBC = ConvertTools.hexStringToByteArray(json)
                        val decryptCBC = SM4Crypt.decrypt(encryptCBC, sm4Key)
                        json = String(decryptCBC)
                    }
                    val resp: BaseResponse<String> =
                        Gson().fromJson(json, object : TypeToken<BaseResponse<String>>() {}.type)
                    if (resp.code == 200) {
                        XToastUtils.success(getString(R.string.request_succeeded))
                    } else {
                        XToastUtils.error(getString(R.string.request_failed) + resp.msg)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.e(TAG, e.toString())
                    XToastUtils.error(getString(R.string.request_failed) + response)
                }
                pushCountDownHelper?.finish()
            }
        })

    }

    //拉取配置
    private fun pullData() {
        if (!CommonUtils.checkUrl(HttpServerUtils.serverAddress)) {
            XToastUtils.error(getString(R.string.invalid_service_address))
            return
        }

        exportCountDownHelper?.start()

        val requestUrl: String = HttpServerUtils.serverAddress + "/clone/pull"
        Log.i(TAG, "requestUrl:$requestUrl")

        val msgMap: MutableMap<String, Any> = mutableMapOf()
        val timestamp = System.currentTimeMillis()
        msgMap["timestamp"] = timestamp
        val clientSignKey = HttpServerUtils.clientSignKey
        if (!TextUtils.isEmpty(clientSignKey)) {
            msgMap["sign"] = HttpServerUtils.calcSign(timestamp.toString(), clientSignKey)
        }

        val dataMap: MutableMap<String, Any> = mutableMapOf()
        dataMap["version_code"] = AppUtils.getAppVersionCode()
        msgMap["data"] = dataMap

        var requestMsg: String = Gson().toJson(msgMap)
        Log.i(TAG, "requestMsg:$requestMsg")

        val postRequest = XHttp.post(requestUrl).keepJson(true).timeStamp(true)

        when (HttpServerUtils.clientSafetyMeasures) {
            2 -> {
                val publicKey = RSACrypt.getPublicKey(HttpServerUtils.clientSignKey)
                try {
                    requestMsg = Base64.encode(requestMsg.toByteArray())
                    requestMsg = RSACrypt.encryptByPublicKey(requestMsg, publicKey)
                    Log.i(TAG, "requestMsg: $requestMsg")
                } catch (e: Exception) {
                    XToastUtils.error(getString(R.string.request_failed) + e.message)
                    e.printStackTrace()
                    Log.e(TAG, e.toString())
                    return
                }
                postRequest.upString(requestMsg)
            }

            3 -> {
                try {
                    val sm4Key = ConvertTools.hexStringToByteArray(HttpServerUtils.clientSignKey)
                    //requestMsg = Base64.encode(requestMsg.toByteArray())
                    val encryptCBC = SM4Crypt.encrypt(requestMsg.toByteArray(), sm4Key)
                    requestMsg = ConvertTools.bytes2HexString(encryptCBC)
                    Log.i(TAG, "requestMsg: $requestMsg")
                } catch (e: Exception) {
                    XToastUtils.error(getString(R.string.request_failed) + e.message)
                    e.printStackTrace()
                    Log.e(TAG, e.toString())
                    return
                }
                postRequest.upString(requestMsg)
            }

            else -> {
                postRequest.upJson(requestMsg)
            }
        }

        postRequest.execute(object : SimpleCallBack<String>() {
            override fun onError(e: ApiException) {
                XToastUtils.error(e.displayMessage)
                exportCountDownHelper?.finish()
            }

            override fun onSuccess(response: String) {
                Log.i(TAG, response)
                try {
                    var json = response
                    if (HttpServerUtils.clientSafetyMeasures == 2) {
                        val publicKey = RSACrypt.getPublicKey(HttpServerUtils.clientSignKey)
                        json = RSACrypt.decryptByPublicKey(json, publicKey)
                        json = String(Base64.decode(json))
                    } else if (HttpServerUtils.clientSafetyMeasures == 3) {
                        val sm4Key =
                            ConvertTools.hexStringToByteArray(HttpServerUtils.clientSignKey)
                        val encryptCBC = ConvertTools.hexStringToByteArray(json)
                        val decryptCBC = SM4Crypt.decrypt(encryptCBC, sm4Key)
                        json = String(decryptCBC)
                    }

                    //替换Date字段为当前时间
                    val builder = GsonBuilder()
                    builder.registerTypeAdapter(
                        Date::class.java,
                        JsonDeserializer<Any?> { _, _, _ -> Date() })
                    val gson = builder.create()
                    val resp: BaseResponse<CloneInfo> =
                        gson.fromJson(json, object : TypeToken<BaseResponse<CloneInfo>>() {}.type)
                    if (resp.code == 200) {
                        val cloneInfo = resp.data
                        Log.d(TAG, "cloneInfo = $cloneInfo")

                        if (cloneInfo == null) {
                            XToastUtils.error(getString(R.string.request_failed))
                            return
                        }

                        //判断版本是否一致
                        HttpServerUtils.compareVersion(cloneInfo)

                        if (HttpServerUtils.restoreSettings(cloneInfo)) {
                            XToastUtils.success(getString(R.string.import_succeeded))
                        }
                    } else {
                        XToastUtils.error(getString(R.string.request_failed) + resp.msg)
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.e(TAG, e.toString())
                    XToastUtils.error(getString(R.string.request_failed) + response)
                }
                exportCountDownHelper?.finish()
            }
        })

    }

    override fun onDestroyView() {
        if (pushCountDownHelper != null) pushCountDownHelper!!.recycle()
        if (pullCountDownHelper != null) pullCountDownHelper!!.recycle()
        if (exportCountDownHelper != null) exportCountDownHelper!!.recycle()
        if (importCountDownHelper != null) importCountDownHelper!!.recycle()
        super.onDestroyView()
    }
}