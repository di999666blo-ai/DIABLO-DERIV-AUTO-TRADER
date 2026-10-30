package com.diablo.derivtrader
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

class MainActivity:AppCompatActivity(){
 private val api=DerivApi()
 private lateinit var log:TextView
 private lateinit var pat:EditText
 private lateinit var appId:EditText
 private lateinit var account:EditText
 private lateinit var symbol:EditText
 private lateinit var prefs:android.content.SharedPreferences
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
  prefs=try{val key=MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();EncryptedSharedPreferences.create(this,"diablo_secure",key,EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)}catch(e:Exception){getSharedPreferences("diablo_credentials",MODE_PRIVATE)}
  render();pat.setText(prefs.getString("pat",""));appId.setText(prefs.getString("appId",""))
  api.onUpdate={m->runOnUiThread{val c=m.optJSONObject("proposal_open_contract");if(c!=null)show("Contract ${c.optLong("contract_id")} | ${c.optString("status")} | P/L ${c.optDouble("profit")}")}}
 }
 private fun render(){
  val scroll=ScrollView(this);val box=LinearLayout(this).apply{orientation=1;setPadding(24,22,24,22);setBackgroundColor(0xFF02040A.toInt())}
  fun field(h:String,secret:Boolean=false)=EditText(this).apply{hint=h;setTextColor(-1);setHintTextColor(0xFF8090AA.toInt());setSingleLine(true);if(secret)inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD}
  fun button(s:String,fn:()->Unit)=Button(this).apply{text=s;setOnClickListener{fn()}}
  box.addView(TextView(this).apply{text="DIABLO DERIV TRADER";textSize=24f;gravity=Gravity.CENTER;setTextColor(0xFFFF0A1F.toInt())})
  pat=field("Deriv PAT with trade permission",true);appId=field("Deriv App ID");account=field("Deriv Options account ID");symbol=field("Market symbol e.g. 1HZ100V")
  listOf("PERSONAL ACCESS TOKEN","APP ID","DEMO ACCOUNT ID","MARKET SYMBOL").forEachIndexed{i,s->box.addView(TextView(this).apply{text=s;setTextColor(0xFF9EB0CA.toInt())});box.addView(listOf(pat,appId,account,symbol)[i])}
  box.addView(button("CONNECT DERIV DEMO"){connect()})
  box.addView(button("LOAD MARKETS"){api.markets{r->runOnUiThread{r.onSuccess{show("Markets loaded: "+it.optJSONArray("active_symbols")?.length())}.onFailure{show(it.message?:"Market error")}}}})
  box.addView(button("CHECK MULTIPLIER AVAILABILITY"){val s=symbol.text.toString().trim();api.contractsFor(s){r->runOnUiThread{r.onSuccess{show("Deriv contract list received for $s. Check available MULTUP/MULTDOWN types.")}.onFailure{show(it.message?:"Contract check error")}}}})
  box.addView(button("STOP / DISCONNECT"){api.disconnect();show("Disconnected. No new trades will be started. Existing contracts remain on Deriv.")})
  log=TextView(this).apply{text="DEMO ONLY — not yet enabled for automatic trading.";textSize=14f;setTextColor(0xFF00FF7A.toInt());setPadding(12,20,12,20)}
  box.addView(log);box.addView(TextView(this).apply{text="Multipliers can lose money. Real mode and auto-execution remain locked until demo strategy, risk controls and trade lifecycle are tested.";setTextColor(0xFFFF9AA5.toInt())})
  scroll.addView(box);setContentView(scroll)
 }
 private fun connect(){val t=pat.text.toString().trim();val a=appId.text.toString().trim();val id=account.text.toString().trim();if(t.isBlank()||a.isBlank()||id.isBlank()){show("Enter PAT, App ID and account ID.");return};prefs.edit().putString("pat",t).putString("appId",a).apply();show("Connecting to Deriv Demo…");api.connect(t,a,id,"demo"){r->runOnUiThread{r.onSuccess{show(it)}.onFailure{show("Connection failed: ${it.message}")}}}}
 private fun show(s:String){if(::log.isInitialized)log.text=s}
 override fun onStop(){super.onStop();api.disconnect()}
}
