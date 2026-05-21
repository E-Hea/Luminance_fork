package com.example.luminance.ui.hazard

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.luminance.R
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng

class FullMapActivity : AppCompatActivity(), OnMapReadyCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_map)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.fullMap)
                as SupportMapFragment

        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {

        val seoul = LatLng(37.5665, 126.9780)

        map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(seoul, 17f)
        )
    }
}