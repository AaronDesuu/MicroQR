package com.example.microqr

// import androidx.navigation.ui.setupActionBarWithNavController // Uncomment if you have a top Toolbar
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import com.example.microqr.databinding.ActivityMainBinding
import com.example.microqr.ui.reader.ReaderFragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
        navController = navHostFragment.navController

        // If you have a top Toolbar:
        // val toolbar = binding.toolbar // Assuming you have <androidx.appcompat.widget.Toolbar android:id="@+id/toolbar" .../>
        // setSupportActionBar(toolbar)
        // setupActionBarWithNavController(navController, appBarConfiguration)

        val navView: BottomNavigationView = binding.navView
        navView.setupWithNavController(navController)

        // Listen for destination changes
        navController.addOnDestinationChangedListener { _, destination, _ ->
            // Check the ID of the destination
            // Assuming your LoginFragment in the nav_graph.xml has an id like R.id.loginFragment
            if (destination.id == R.id.loginFragment) {
                navView.visibility = View.GONE
                supportActionBar?.hide() // Hide ActionBar/Toolbar if present
            } else {
                navView.visibility = View.VISIBLE
                supportActionBar?.show() // Show ActionBar/Toolbar if present
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}