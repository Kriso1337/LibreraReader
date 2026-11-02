package com.foobnix.ui2;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.LocaleList;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.viewpager.widget.ViewPager;

import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.wrapper.DocumentController;
import com.foobnix.ui2.adapter.FileMetaAdapter;
import com.foobnix.ui2.fragment.UIFragment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native library tabs; screenshots use an in-memory adapter, never the user's book metadata. */
@RunWith(AndroidJUnit4.class)
public class LibraryDesignDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private ActivityScenario<MainTabs2> host;
    private String oldOrder, oldLanguage;
    private int oldTheme, oldCurrentTab, oldRotation, oldAutoRotation, oldOrientation;
    private boolean tablet;
    private boolean oldDay,oldSystemTheme,oldOffline,hadOffline;
    private float oldScale;
    private LocaleList oldLocales;

    @Before public void before() {
        instrumentation=InstrumentationRegistry.getInstrumentation(); context=instrumentation.getTargetContext();
        AppProfile.init(context);
        tablet=context.getResources().getConfiguration().smallestScreenWidthDp>=600;
        oldRotation=Settings.System.getInt(context.getContentResolver(),Settings.System.USER_ROTATION,0);
        oldAutoRotation=Settings.System.getInt(context.getContentResolver(),Settings.System.ACCELEROMETER_ROTATION,1);
        oldCurrentTab=com.foobnix.sys.TempHolder.get().currentTab;
        AppState s=AppState.get(); oldOrder=s.tabsOrder9; oldLanguage=s.appLang;oldTheme=s.appTheme;
        oldOrientation=s.orientation;
        oldDay=s.isDayNotInvert;oldSystemTheme=s.isSystemThemeColor;oldScale=BookCSS.get().appFontScale;oldLocales=LocaleList.getDefault();
        SharedPreferences prefs=context.getSharedPreferences("catalog-ui",Context.MODE_PRIVATE);
        hadOffline=prefs.contains("offline");oldOffline=prefs.getBoolean("offline",false);prefs.edit().putBoolean("offline",true).commit();
        s.tabsOrder9="0#1,1#1,2#1,3#1,4#1,5#1,6#1,7#0,8#1";s.appLang="ru";s.isSystemThemeColor=false;
    }
    @After public void after() throws Exception {
        AppState s=AppState.get();s.tabsOrder9=oldOrder;s.appLang=oldLanguage;s.appTheme=oldTheme;
        s.orientation=oldOrientation;
        s.isDayNotInvert=oldDay;s.isSystemThemeColor=oldSystemTheme;BookCSS.get().appFontScale=oldScale;
        SharedPreferences.Editor edit=context.getSharedPreferences("catalog-ui",Context.MODE_PRIVATE).edit();
        if(hadOffline)edit.putBoolean("offline",oldOffline);else edit.remove("offline");edit.commit();
        if(host!=null)host.close();com.foobnix.sys.TempHolder.get().currentTab=oldCurrentTab;LocaleList.setDefault(oldLocales);
        // Samsung can remember an Activity-requested rotation even without a test
        // writing system settings. Restore only if the OS changed either value.
        if(Settings.System.getInt(context.getContentResolver(),Settings.System.USER_ROTATION,0)!=oldRotation
                || Settings.System.getInt(context.getContentResolver(),Settings.System.ACCELEROMETER_ROTATION,1)!=oldAutoRotation) {
            shell("cmd window user-rotation lock "+oldRotation);
            if(oldAutoRotation==1)shell("cmd window user-rotation free");
        }
    }
    @Test public void automaticOrientationFollowsSystemRotationLockAfterRecreation() throws Exception {
        org.junit.Assume.assumeFalse("This acceptance check uses a portrait-native phone",tablet);
        launch(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,1,false);
        host.onActivity(activity->{
            AppState.get().orientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR;
            DocumentController.doRotation(activity);
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER,activity.getRequestedOrientation());
        });
        shell("cmd window user-rotation lock 0");
        awaitOrientation(true);
        shell("cmd window user-rotation lock 1");
        awaitOrientation(false);
        host.recreate();
        awaitOrientation(false);
        host.onActivity(activity->{
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER,activity.getRequestedOrientation());
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR,AppState.get().orientation);
        });
        shell("cmd window user-rotation free");
        host.onActivity(activity->assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER,activity.getRequestedOrientation()));
    }
    @Test public void fixedOrientationsAndAxisChoicesPreserveTheirScope() throws Exception {
        org.junit.Assume.assumeFalse("This acceptance check uses a portrait-native phone",tablet);
        launch(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,1,false);
        shell("cmd window user-rotation lock 0");
        int[][] choices={
                {ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_PORTRAIT},
                {ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE},
                {ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT},
                {ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE},
                {ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}
        };
        for(int[] choice:choices) {
            host.onActivity(activity->{
                AppState.get().orientation=choice[0];
                DocumentController.doRotation(activity);
                assertEquals(choice[1],activity.getRequestedOrientation());
            });
            if(choice[0]!=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                awaitOrientation(choice[0]==ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        || choice[0]==ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }
    }
    private void awaitOrientation(boolean portrait) {
        for(int i=0;i<80;i++) {
            AtomicBoolean ready=new AtomicBoolean();
            host.onActivity(activity->{
                View pager=activity.findViewById(R.id.pager);
                ready.set(pager.getWidth()>0 && (portrait?pager.getHeight()>pager.getWidth():pager.getWidth()>pager.getHeight()));
            });
            if(ready.get())return;
            SystemClock.sleep(100);
        }
        fail("The library did not follow the selected orientation");
    }
    @Test public void portraitTabsKeepAllExistingToolbarActions() throws Exception {
        launch(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,1,false);
        host.onActivity(a->{
            for (Fragment fragment : a.getSupportFragmentManager().getFragments()) {
                if (fragment instanceof com.foobnix.ui2.fragment.CatalogFragment && !fragment.getUserVisibleHint()) {
                    try {
                        java.lang.reflect.Field field=fragment.getClass().getDeclaredField("initialRequestStarted");field.setAccessible(true);
                        assertFalse("Hidden catalogue does not start a foreground request",field.getBoolean(fragment));
                    } catch(ReflectiveOperationException error) {throw new AssertionError(error);}
                }
            }
        });
        int[][] required={{R.id.menu2,R.id.sortBy,R.id.sortOrder,R.id.onGridList},
                {R.id.onBack,R.id.onHome,R.id.onListGrid}, {R.id.onListGrid},
                {R.id.onTags,R.id.onPlaylists,R.id.onListGrid}, {R.id.onSettings,R.id.search,R.id.onListGrid},
                {R.id.onBack,R.id.onHome,R.id.onPlus}, {R.id.onCloseApp}};
        for(int tab=0;tab<required.length;tab++) {
            select(tab);
            final int[] actions=required[tab];
            final boolean settings=tab==6;
            host.onActivity(a->{
                View root=current(a).requireView();
                for(int id:actions)assertNotNull("Existing toolbar action is retained: "+id,root.findViewById(id));
                assertHeaders(root);
                if(settings)assertSectionContrast(root.findViewById(R.id.section1));
            });
            if(tab==0 || tab==2 || tab==3) {
                fixtureBooks(tab==0); screenshot("library-portrait-tab-"+tab);
            } else screenshot("library-portrait-tab-"+tab);
        }
    }
    @Test public void landscapeCardsAndCallbacksRemainUsable() throws Exception {
        launch(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,1,false);select(2);fixtureBooks(false);
        AtomicBoolean clicked=new AtomicBoolean();
        host.onActivity(a->{
            View root=current(a).requireView();assertHeaders(root);
            RecyclerView list=root.findViewById(R.id.recyclerView);
            assertTrue("List retains useful height",list.getHeight()/a.getResources().getDisplayMetrics().density>=100);
            FileMetaAdapter adapter=(FileMetaAdapter)list.getAdapter();
            adapter.setOnItemClickListener(book->{clicked.set(true);return true;});
            assertNotNull(list.getChildAt(0));list.getChildAt(0).performClick();
            assertTrue("Existing book item callback is preserved",clicked.get());
            assertTrue("Book title remains visible",list.getChildAt(0).findViewById(R.id.title1).isShown());
            View jacket=list.getChildAt(0).findViewById(R.id.browserItemIcon);
            assertEquals("List jackets keep their proportions when metadata makes rows taller",1.4f,(float)jacket.getHeight()/jacket.getWidth(),.03f);
        });
        screenshot("library-landscape-list");
    }
    @Test public void portraitLibraryKeepsThreeRowsAndRevealsSecondaryActionsOnDemand() throws Exception {
        launch(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,1.15f,false);select(0);fixtureBooks(true);
        host.onActivity(a->{
            View root=current(a).requireView();
            View toggle=root.findViewWithTag("library-header-actions");
            assertNotNull(toggle);assertTrue(toggle.isShown());
            assertFalse(root.findViewById(R.id.sortBy).isShown());
            RecyclerView list=root.findViewById(R.id.recyclerView);
            GridLayoutManager grid=(GridLayoutManager)list.getLayoutManager();
            View thirdRow=grid.findViewByPosition(grid.getSpanCount()*3-1);
            assertNotNull("Three book rows are laid out",thirdRow);
            assertTrue("Three whole book rows fit above navigation",bounds(thirdRow).bottom<=bounds(list).bottom-list.getPaddingBottom());
            toggle.performClick();
        });
        settle();
        host.onActivity(a->{
            View root=current(a).requireView();
            assertTrue("Sort remains available after expanding actions",root.findViewById(R.id.sortBy).isShown());
            assertHeaders(root);
            root.findViewWithTag("library-header-actions").performClick();
        });
        settle();screenshot("library-three-rows");
    }
    @Test public void gridMenuKeepsFavoritesAvailableWithoutPermanentButtons() throws Exception {
        launch(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,1.15f,false);select(0);fixtureBooks(true);
        AtomicBoolean favorite=new AtomicBoolean();
        host.onActivity(a->{
            RecyclerView list=current(a).requireView().findViewById(R.id.recyclerView);
            FileMetaAdapter adapter=(FileMetaAdapter)list.getAdapter();
            adapter.setOnMenuClickListener(book -> true);
            adapter.setOnStarClickListener((book,source) -> {favorite.set(true);return true;});
            assertFalse(list.getChildAt(0).findViewById(R.id.starIcon).isShown());
            list.getChildAt(0).findViewById(R.id.itemMenu).performClick();
        });
        settle();screenshot("library-book-actions");
        AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();
        assertNotNull(root);
        List<AccessibilityNodeInfo> matches=root.findAccessibilityNodeInfosByText(context.getString(R.string.add_to_favorites));
        assertFalse("Favorite action is exposed in the book menu",matches.isEmpty());
        AccessibilityNodeInfo action=matches.get(0);
        while(action!=null && !action.isClickable())action=action.getParent();
        assertNotNull(action);assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        settle();assertTrue("Original favorite callback is retained",favorite.get());
    }
    @Test public void largeDarkTextReflowsToolbarsAndBookGrid() throws Exception {
        launch(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,2,true);
        for(int tab:new int[]{0,3,4,6}) {
            select(tab);host.onActivity(a->{
                View root=current(a).requireView();assertHeaders(root);
                if(tab==6)assertSettingsText(root);
            });
            if(tab==0){fixtureBooks(true);screenshot("library-dark-large-grid");}
        }
    }
    private void launch(int orientation,float scale,boolean dark) throws Exception {
        BookCSS.get().appFontScale=scale;AppState.get().appTheme=dark?AppState.THEME_DARK:AppState.THEME_LIGHT;
        AppState.get().isDayNotInvert=!dark;
        host=ActivityScenario.launch(new Intent(context,MainTabs2.class).putExtra(MainTabs2.EXTRA_SHOW_TABS,true).putExtra(MainTabs2.EXTRA_PAGE_NUMBER,0));
        if(tablet) {
            // Android 16 large screens ignore the Activity request. Only the tablet
            // emulator display is rotated; the physical phone's system settings stay intact.
            shell("cmd window user-rotation lock "+(orientation==ActivityInfo.SCREEN_ORIENTATION_PORTRAIT?1:0));
        } else host.onActivity(a->a.setRequestedOrientation(orientation));
        for(int i=0;i<80;i++) {
            AtomicBoolean ready=new AtomicBoolean();
            host.onActivity(a->{View v=a.findViewById(R.id.pager);ready.set(v.getWidth()>0 && (orientation==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE?v.getWidth()>v.getHeight():v.getHeight()>v.getWidth()));});
            if(ready.get()){settle();return;}SystemClock.sleep(100);
        }
        fail("Window did not reach orientation");
    }
    private void select(int tab) {host.onActivity(a->((ViewPager)a.findViewById(R.id.pager)).setCurrentItem(tab,false));settle();}
    private UIFragment<?> current(MainTabs2 activity) {
        int current=((ViewPager)activity.findViewById(R.id.pager)).getCurrentItem();
        Fragment f=activity.tabFragments.get(current);
        assertTrue("Real library tab mounted",f instanceof UIFragment);return (UIFragment<?>)f;
    }
    private void fixtureBooks(boolean grid) {
        host.onActivity(a->{
            RecyclerView list=current(a).requireView().findViewById(R.id.recyclerView);
            FileMetaAdapter adapter=new FileMetaAdapter();adapter.setAdapterType(grid?FileMetaAdapter.ADAPTER_GRID:FileMetaAdapter.ADAPTER_LIST);
            String[] titles={"Капитанская дочка: повесть с примечаниями", "Белые ночи", "Палата № 6", "Обломов"};
            String[] authors={"Александр Сергеевич Пушкин","Фёдор Достоевский","Антон Чехов","Иван Гончаров"};
            for(int i=0;i<12;i++) {
                String title=titles[i%titles.length];
                FileMeta book=new FileMeta(new File(context.getCacheDir(),"library-visual-fixtures/"+i+"-"+title+".epub").getPath());
                book.setTitle(title);book.setAuthor(authors[i%authors.length]);book.setPathTxt(title);book.setExt("EPUB");
                book.setCusType(FileMetaAdapter.DISPLAY_TYPE_FILE);book.setIsRecentProgress(.24f);book.setIsStar(false);
                book.setSizeTxt("240 KB");book.setDateTxt("02.11.2026");adapter.getItemsList().add(book);
            }
            float scale=a.getResources().getConfiguration().fontScale;
            int width=Math.round(list.getWidth()/a.getResources().getDisplayMetrics().density);
            list.setLayoutManager(grid?new GridLayoutManager(a,BookshelfLayout.columns(width,scale)):new LinearLayoutManager(a));
            list.setAdapter(adapter);
        });settle();
        awaitCover();
        host.onActivity(a->{
            RecyclerView list=current(a).requireView().findViewById(R.id.recyclerView);
            View card=list.getChildAt(0);assertNotNull(card);
            View star=card.findViewById(R.id.starIcon), menu=card.findViewById(R.id.itemMenu);
            if(grid)assertTrue("Book menu remains directly accessible",menu.isShown());
            if(star.isShown())assertTrue("Book actions keep 8dp gap",bounds(menu).left-bounds(star).right>=8*a.getResources().getDisplayMetrics().density-1);
            TextView format=card.findViewById(R.id.browserExt);assertNotNull(format.getLayout());
            assertTrue("Book format is not truncated",format.getLayout().getEllipsisCount(0)==0 && format.getPaint().measureText(format.getText().toString())<=format.getWidth()+1);
        });
    }
    private void awaitCover() {
        for(int i=0;i<60;i++) {
            AtomicBoolean ready=new AtomicBoolean();
            host.onActivity(a->{
                RecyclerView list=current(a).requireView().findViewById(R.id.recyclerView);
                View card=list.getChildAt(0);
                if(card!=null) {ImageView image=card.findViewById(R.id.browserItemIcon);ready.set(image.getDrawable()!=null);}
            });
            if(ready.get())return;
            SystemClock.sleep(100);
        }
        fail("Generated fixture cover did not finish loading before capture");
    }
    private void assertHeaders(View view) {
        if(view instanceof LibraryHeader) {
            Rect limit=bounds(view);List<View> actions=new ArrayList<>();collectActions(view,actions);
            for(View child:actions) {
                Rect rect=bounds(child);float density=child.getResources().getDisplayMetrics().density;
                assertTrue("Toolbar target >=48dp: "+child.getId(),child.getWidth()+1>=48*density && child.getHeight()+1>=48*density);
                assertTrue("Toolbar action remains within header: "+child.getId(),rect.left>=limit.left-1 && rect.right<=limit.right+1 && rect.bottom<=limit.bottom+1);
            }
            for(int i=0;i<actions.size();i++)for(int j=i+1;j<actions.size();j++)assertFalse("Toolbar actions overlap",Rect.intersects(bounds(actions.get(i)),bounds(actions.get(j))));
        }
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)assertHeaders(((ViewGroup)view).getChildAt(i));
    }
    private void assertSectionContrast(View view) {
        assertNotNull(view);
        if(view instanceof TextView)assertTrue("Section heading is readable on the page",
                androidx.core.graphics.ColorUtils.calculateContrast(((TextView)view).getCurrentTextColor(),LibraryDesign.paper())>=4.5);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)assertSectionContrast(((ViewGroup)view).getChildAt(i));
    }
    private void assertSettingsText(View view) {
        if(!view.isShown())return;
        if(view instanceof TextView && view.isClickable() && view.getGlobalVisibleRect(new Rect())) {
            TextView text=(TextView)view;
            if(text.getLayout()!=null) {
                assertTrue("Setting action fits its text: "+text.getText(),text.getLayout().getHeight()
                        +text.getCompoundPaddingTop()+text.getCompoundPaddingBottom()<=view.getHeight()+1);
                for(int line=0;line<text.getLayout().getLineCount();line++)
                    assertEquals("Setting action is not truncated: "+text.getText(),0,text.getLayout().getEllipsisCount(line));
            }
        }
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)assertSettingsText(((ViewGroup)view).getChildAt(i));
    }
    private void collectActions(View v,List<View> out) {
        if(!v.isShown())return;
        if(v instanceof ImageView && v.isClickable())out.add(v);
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collectActions(((ViewGroup)v).getChildAt(i),out);
    }
    private Rect bounds(View v){int[]p=new int[2];v.getLocationOnScreen(p);return new Rect(p[0],p[1],p[0]+v.getWidth(),p[1]+v.getHeight());}
    private void shell(String command)throws Exception {
        try(ParcelFileDescriptor fd=instrumentation.getUiAutomation().executeShellCommand(command);
            java.io.InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            byte[] buffer=new byte[256];while(input.read(buffer)!=-1) { }
        }
    }
    private void settle(){instrumentation.waitForIdleSync();SystemClock.sleep(450);instrumentation.waitForIdleSync();}
    private void screenshot(String name)throws Exception{
        Bitmap bitmap=instrumentation.getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        File folder=new File(context.getExternalFilesDir(null),"tests-library");assertTrue(folder.isDirectory()||folder.mkdirs());
        try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle();
    }
}
