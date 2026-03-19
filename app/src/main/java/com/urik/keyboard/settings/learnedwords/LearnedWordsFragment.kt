package com.urik.keyboard.settings.learnedwords

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.urik.keyboard.R
import com.urik.keyboard.settings.SettingsEventHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LearnedWordsFragment : Fragment() {
    private lateinit var viewModel: LearnedWordsViewModel
    private lateinit var eventHandler: SettingsEventHandler
    private lateinit var adapter: LearnedWordsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[LearnedWordsViewModel::class.java]
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_learned_words, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        eventHandler = SettingsEventHandler(requireContext())

        val recyclerView = view.findViewById<RecyclerView>(R.id.learned_words_list)
        val emptyView = view.findViewById<TextView>(R.id.learned_words_empty)
        val loadingView = view.findViewById<ProgressBar>(R.id.learned_words_loading)
        val contentView = view.findViewById<View>(R.id.learned_words_content)
        val sideBar = view.findViewById<AlphabetSideBar>(R.id.alphabet_sidebar)

        adapter = LearnedWordsAdapter { word -> viewModel.deleteWord(word) }
        val layoutManager = LinearLayoutManager(requireContext())
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter

        var sectionPositions = emptyMap<String, Int>()

        sideBar.setOnLetterSelectedListener { letter ->
            val position = sectionPositions[letter]
            if (position != null) {
                layoutManager.scrollToPositionWithOffset(position, 0)
            }
        }

        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.menu_learned_words, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    if (menuItem.itemId == R.id.action_delete_all) {
                        showDeleteAllConfirmation()
                        return true
                    }
                    return false
                }
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED
        )

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        loadingView.visibility = if (state.isLoading) View.VISIBLE else View.GONE

                        if (!state.isLoading) {
                            if (state.words.isEmpty()) {
                                contentView.visibility = View.GONE
                                emptyView.visibility = View.VISIBLE
                            } else {
                                contentView.visibility = View.VISIBLE
                                emptyView.visibility = View.GONE
                                val positions = mutableMapOf<String, Int>()
                                state.words.forEachIndexed { index, word ->
                                    val letter = word.firstOrNull()?.uppercase() ?: return@forEachIndexed
                                    positions.putIfAbsent(letter, index)
                                }
                                sectionPositions = positions
                                sideBar.setLetters(positions.keys.toList())
                            }
                            adapter.submitList(state.words)
                        }
                    }
                }

                launch {
                    viewModel.events.collect { event ->
                        eventHandler.handle(event)
                    }
                }
            }
        }
    }

    private fun showDeleteAllConfirmation() {
        AlertDialog
            .Builder(requireContext())
            .setTitle(resources.getString(R.string.learned_words_delete_all))
            .setMessage(resources.getString(R.string.learned_words_delete_all_confirm))
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.deleteAllWords() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
