package com.example.soundvisualizer.language

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit

/**
 * 앱 언어 설정. 고른 언어가 없으면(null) 폰 언어를 따르고, 지원하지 않는 폰 언어면 영어(values)로 보인다.
 *
 * - Android 13 이상: 시스템의 앱별 언어([LocaleManager])를 쓴다. 시스템이 저장하고, 바뀌면 화면을 알아서 다시 만든다.
 *   폰 설정의 "앱 언어"에서 바꾼 값과 같은 값이라 어느 쪽에서 바꿔도 맞는다.
 * - Android 12 이하: 전용 SharedPreferences 파일에 태그를 저장한다. 글자를 보여주는 액티비티와 서비스가
 *   attachBaseContext 에서 [wrap] 으로 컨텍스트의 언어를 바꾸고, 바꾼 뒤에는 액티비티를 직접 다시 만든다.
 *   applicationContext 는 바뀌지 않으므로 거기서 문구를 꺼내지 않는다(토스트도 액티비티에서 꺼낸 문구로 띄운다).
 *
 * 저장 파일은 [com.example.soundvisualizer.SettingsManager] 와 따로 둔다. attachBaseContext 는 SettingsManager.init 보다 먼저 불린다.
 */
object AppLanguage {

    private const val PREFS_NAME = "AppLanguagePrefs"
    private const val KEY_TAG = "tag"

    /** 사용자가 고른 언어 태그. 폰 언어를 따르면 null. */
    fun selectedTag(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) null else locales[0].toLanguageTag()
        } else {
            prefs(context).getString(KEY_TAG, null)
        }

    /**
     * 앱 언어를 바꾼다. null 이면 폰 언어를 따른다.
     * 이미 그 언어면 아무것도 하지 않는다. 바뀌면 [activity] 가 다시 만들어진다.
     */
    fun select(activity: Activity, tag: String?) {
        if (tag == selectedTag(activity)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = localesOf(tag)
        } else {
            prefs(activity).edit { if (tag == null) remove(KEY_TAG) else putString(KEY_TAG, tag) }
            activity.recreate()
        }
    }

    /**
     * Android 12 이하에서 고른 언어로 문구를 꺼내는 컨텍스트. attachBaseContext 에 넘긴다.
     * 13 이상은 시스템이 앱 전체에 적용하므로 그대로 돌려준다.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = prefs(base).getString(KEY_TAG, null)
        // 기본 로캘도 맞춘다. Compose 글자는 기본 로캘로 글꼴(한자 모양 등)과 줄바꿈을 고른다.
        // 폰 언어로 되돌렸을 때 이전 선택이 남지 않도록, 고른 언어가 없으면 폰 언어로 다시 맞춘다.
        LocaleList.setDefault(if (tag == null) Resources.getSystem().configuration.locales else localesOf(tag))
        if (tag == null) return base
        val config = Configuration(base.resources.configuration).apply { setLocales(localesOf(tag)) }
        return base.createConfigurationContext(config)
    }

    /**
     * Android 12 이하에서 고른 언어가 폰을 13 이상으로 올린 뒤에도 남도록 시스템 설정으로 옮긴다.
     * 13 이상에서 앱을 열 때 부른다. 옮길 값이 없으면 아무것도 하지 않는다.
     */
    fun migrateLegacyChoice(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = prefs(activity)
        val legacy = prefs.getString(KEY_TAG, null) ?: return
        prefs.edit { remove(KEY_TAG) }
        val manager = activity.getSystemService(LocaleManager::class.java)
        // 그사이 폰 설정에서 언어를 골랐으면 그쪽을 따른다.
        if (manager.applicationLocales.isEmpty) manager.applicationLocales = localesOf(legacy)
    }

    private fun localesOf(tag: String?): LocaleList =
        if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
