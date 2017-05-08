package info.staticfree.mqtt_camera.activity;

import android.app.Fragment;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import info.staticfree.mqtt_camera.R;
import info.staticfree.mqtt_camera.fragment.CameraFragment;
import info.staticfree.mqtt_camera.fragment.KeepScreenOnFragment;

public class CameraActivity extends AppCompatActivity {
    private ActionBarDrawerToggle drawerToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        DrawerLayout drawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        ListView leftDrawer = (ListView) findViewById(R.id.left_drawer);
        drawerToggle = new ActionBarDrawerToggle(this, drawerLayout, R.string.drawer_open,
                R.string.drawer_close);

        leftDrawer.setOnItemClickListener(new DrawerItemClickListener());

        drawerLayout.addDrawerListener(drawerToggle);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
        }

        String[] items = getResources().getStringArray(R.array.drawer_options);
        leftDrawer.setAdapter(
                new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items));
        showCamera();

        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .add(new KeepScreenOnFragment(), KeepScreenOnFragment.class.getName()).commit();
        }
    }

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        drawerToggle.syncState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        drawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return drawerToggle.onOptionsItemSelected(item) || super.onOptionsItemSelected(item);
    }

    private void showSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void showCamera() {
        Fragment fragment =
                getFragmentManager().findFragmentByTag(CameraFragment.class.getName());

        if (fragment == null) {
            fragment = new CameraFragment();
        }

        getFragmentManager().beginTransaction()
                .replace(R.id.fragment_content, fragment, CameraFragment.class.getName())
                .commit();
    }

    private class DrawerItemClickListener implements AdapterView.OnItemClickListener {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            if (position == 0) {
                showCamera();
            } else if (position == 1) {
                showSettings();
            }
        }
    }
}
