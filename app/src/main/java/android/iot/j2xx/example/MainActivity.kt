package android.iot.j2xx.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import com.ftdi.j2xx.d2xx.D2xxController

class MainActivity : AppCompatActivity(), D2xxController.D2xxEvent {
    override fun onOpenedDev(port: Int) {
        Log.i("xxa", "${D2xxController.with(this).isOpenedDev}" )
        D2xxController.with(this).write("00 01 ab")
    }

    override fun onDevAttached(context: Context?, intent: Intent?) {
    }

    override fun onDevDetached(context: Context?, intent: Intent?) {
    }

    override fun onRead(hex: String?) {
        Log.i("xxa", "hex:" + hex)
        if ("FF" == hex) {
            D2xxController.with(this).write("00 01 AB")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        D2xxController.with(this).setD2xxEvent(this).attachedPuppet(this)


    }
}
