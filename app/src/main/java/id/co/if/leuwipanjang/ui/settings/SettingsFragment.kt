package id.co.if.leuwipanjang.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import id.co.if.leuwipanjang.data.model.TerminalTheme
import id.co.if.leuwipanjang.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = requireContext().getSharedPreferences("leuwipanjang_settings", Context.MODE_PRIVATE)

        // Setup Theme Spinner
        val themeNames = TerminalTheme.ALL_THEMES.map { it.name }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, themeNames)
        binding.spDefaultTheme.adapter = adapter

        val savedThemeId = prefs.getString("default_theme", "termius_dark")
        val themeIdx = TerminalTheme.ALL_THEMES.indexOfFirst { it.id == savedThemeId }
        if (themeIdx >= 0) {
            binding.spDefaultTheme.setSelection(themeIdx)
        }

        binding.spDefaultTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                if (position in TerminalTheme.ALL_THEMES.indices) {
                    val theme = TerminalTheme.ALL_THEMES[position]
                    prefs.edit().putString("default_theme", theme.id).apply()
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        // Setup Font Size Slider
        val savedFontSize = prefs.getInt("default_font_size", 14)
        binding.sliderFontSize.value = savedFontSize.toFloat()
        binding.tvFontSizeLabel.text = "Font Size: ${savedFontSize}sp"

        binding.sliderFontSize.addOnChangeListener { _, value, _ ->
            val size = value.toInt()
            binding.tvFontSizeLabel.text = "Font Size: ${size}sp"
            prefs.edit().putInt("default_font_size", size).apply()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
