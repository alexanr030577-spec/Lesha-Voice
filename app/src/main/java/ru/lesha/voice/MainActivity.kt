package ru.lesha.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Intent
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
 private lateinit var status: TextView
 private var sr: SpeechRecognizer? = null
 private var tts: TextToSpeech? = null
 private var listening = false

 override fun onCreate(b: Bundle?) {
  super.onCreate(b); tts=TextToSpeech(this,this)
  val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(48,100,48,48)}
  status=TextView(this).apply{text="Готова услышать «Лёша»";textSize=24f}
  val btn=Button(this).apply{text="СЛУШАТЬ";setOnClickListener{ if(listening) stop() else start() }}
  box.addView(status);box.addView(btn);setContentView(box)
 }
 override fun onInit(s:Int){ if(s==TextToSpeech.SUCCESS) tts?.language=Locale("ru","RU") }
 private fun start(){
  if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
   requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),7); return
  }
  if(!SpeechRecognizer.isRecognitionAvailable(this)){status.text="Распознавание речи недоступно";return}
  listening=true
  sr=SpeechRecognizer.createSpeechRecognizer(this).also{ r->
   r.setRecognitionListener(object:RecognitionListener{
    override fun onResults(b:Bundle){ check(b); restart() }
    override fun onPartialResults(b:Bundle){ check(b) }
    override fun onError(e:Int){ if(listening) restart() }
    override fun onReadyForSpeech(p:Bundle?){}; override fun onBeginningOfSpeech(){}
    override fun onRmsChanged(v:Float){}; override fun onBufferReceived(b:ByteArray?){}
    override fun onEndOfSpeech(){}; override fun onEvent(t:Int,p:Bundle?){}
   })
  }; listen()
 }
 private fun intent()=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{
  putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
  putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ru-RU"); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
 }
 private fun listen(){ status.text="Слушаю… скажи «Лёша»"; sr?.startListening(intent()) }
 private fun check(b:Bundle){
  val xs=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?:return
  if(xs.any{it.lowercase(Locale("ru","RU")).contains("лёша") || it.lowercase(Locale("ru","RU")).contains("леша")}){
   status.text="Услышала: Лёша"; tts?.speak("Да?",TextToSpeech.QUEUE_FLUSH,null,"yes")
  }
 }
 private fun restart(){ if(!listening)return; status.postDelayed({if(listening) try{sr?.startListening(intent())}catch(_:Exception){}},350) }
 private fun stop(){listening=false;sr?.cancel();status.text="Остановлено"}
 override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==7&&g.firstOrNull()==PackageManager.PERMISSION_GRANTED)start()}
 override fun onDestroy(){listening=false;sr?.destroy();tts?.shutdown();super.onDestroy()}
}
