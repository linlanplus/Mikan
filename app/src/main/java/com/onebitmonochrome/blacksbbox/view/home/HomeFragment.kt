package com.onebitmonochrome.blacksbbox.view.home

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.doOnPreDraw
import androidx.core.view.drawToBitmap
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.SamsungHealthCompat
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.app.AppFreezeManager
import com.onebitmonochrome.blacksbbox.bean.AppInfo
import com.onebitmonochrome.blacksbbox.data.CloneStore
import com.onebitmonochrome.blacksbbox.databinding.FragmentHomeBinding
import com.onebitmonochrome.blacksbbox.util.InjectionUtil
import com.onebitmonochrome.blacksbbox.util.Resolution
import com.onebitmonochrome.blacksbbox.util.ShortcutUtil
import com.onebitmonochrome.blacksbbox.util.inflate
import com.onebitmonochrome.blacksbbox.util.toast
import com.onebitmonochrome.blacksbbox.view.base.LoadingActivity
import com.onebitmonochrome.blacksbbox.view.fake.FakeManagerActivity
import com.onebitmonochrome.blacksbbox.view.gallery.BlackBoxGalleryActivity
import com.onebitmonochrome.blacksbbox.view.main.MainActivity
import com.onebitmonochrome.blacksbbox.view.spoof.DeviceSpoofingActivity
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Home screen: the clones and folders of the current user (group).
 *
 * Long press then release opens an item's menu. Long press and drag moves it: the other icons make
 * room as it passes, holding it over the middle of a clone or folder merges it in, and dragging a
 * clone out of an open folder closes the folder and carries the clone onto the home grid.
 */
class HomeFragment : Fragment(), DragLayer.Listener {

    private val viewBinding: FragmentHomeBinding by inflate()

    private lateinit var viewModel: HomeViewModel

    private lateinit var adapter: HomeAdapter

    private lateinit var folderAdapter: HomeAdapter

    private var group: CloneStore.Group? = null

    private var openFolder: HomeItem.FolderItem? = null

    private var drag: DragState? = null

    /** Layout published while a drag was running; applied when it ends. */
    private var pendingItems: List<HomeItem>? = null

    private var animateNextItems = true

    private val handler = Handler(Looper.getMainLooper())

    private var dwell: Runnable? = null

    private var autoScroll: Runnable? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closeFolder()
        }
    }

    private class DragState(
            val item: HomeItem,
            val floating: ImageView,
            val offsetX: Float,
            val offsetY: Float,
            val startX: Float,
            val startY: Float,
            var inFolder: Boolean
    ) {
        var moved = false
        /** Finger lifted; the icon is animating into place and further touches are ignored. */
        var ending = false
        var lastX = startX
        var lastY = startY
        var mergeKey: String? = null
        var pendingPos = -1
        var pendingMerge = false
        /** Folder the clone was dragged out of, whose contents must be saved too. */
        var leftFolderId: Long? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this, InjectionUtil.getHomeFactory()).get(HomeViewModel::class.java)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        adapter = HomeAdapter(
                onClick = { item, view -> onItemClick(item, view) },
                onLongClick = { item, view -> startDrag(item, view, inFolder = false) }
        )
        folderAdapter = HomeAdapter(
                onClick = { item, view -> onItemClick(item, view) },
                onLongClick = { item, view -> startDrag(item, view, inFolder = true) }
        )
        viewBinding.recyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), 4)
            adapter = this@HomeFragment.adapter
            itemAnimator = smoothAnimator()
            layoutAnimation = AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_pop_in)
        }
        viewBinding.folderRecycler.apply {
            layoutManager = GridLayoutManager(requireContext(), 4)
            adapter = folderAdapter
            itemAnimator = smoothAnimator()
            prewarmFolderViews(this)
        }
        viewBinding.dragLayer.listener = this
        viewBinding.folderScrim.setOnClickListener { closeFolder() }
        viewBinding.folderTitle.setOnClickListener { openFolder?.let { renameFolder(it.folder) } }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        return viewBinding.root
    }

    /**
     * Inflates the folder panel's icon views while the app is idle. Inflating them on the frame a
     * folder opens delayed the first frame of its animation.
     */
    private fun prewarmFolderViews(recycler: RecyclerView) {
        val pool = recycler.recycledViewPool
        pool.setMaxRecycledViews(HomeAdapter.TYPE_CLONE, PREWARMED_FOLDER_VIEWS)
        Looper.myQueue().addIdleHandler {
            if (view != null && openFolder == null) {
                repeat(PREWARMED_FOLDER_VIEWS - pool.getRecycledViewCount(HomeAdapter.TYPE_CLONE)) {
                    pool.putRecycledView(folderAdapter.createViewHolder(recycler, HomeAdapter.TYPE_CLONE))
                }
            }
            false
        }
    }

    private fun smoothAnimator() = DefaultItemAnimator().apply {
        moveDuration = 220
        addDuration = 200
        removeDuration = 160
        supportsChangeAnimations = false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.groupLiveData.observe(viewLifecycleOwner) {
            group = it
            (activity as? MainActivity)?.groupTitle()?.let { title ->
                title.groupName.text = it.name
                title.groupCount.text = getString(R.string.group_count, CloneStore.cloneCount(it.id))
                title.root.setOnClickListener { anchor -> showGroupMenu(anchor) }
            }
        }
        viewModel.itemsLiveData.observe(viewLifecycleOwner) { items ->
            if (drag != null) {
                pendingItems = items
            } else {
                showItems(items)
            }
        }
        viewModel.resultLiveData.observe(viewLifecycleOwner) {
            if (!it.isNullOrEmpty()) {
                hideLoading()
                toast(it)
                viewModel.resultLiveData.value = null
            }
        }
        viewModel.launchLiveData.observe(viewLifecycleOwner) {
            if (it != null) {
                hideLoading()
                if (!it) toast(R.string.start_fail)
                viewModel.launchLiveData.value = null
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            BlackBoxCore.get().addServiceAvailableCallback { viewModel.refresh() }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering service callback: ${e.message}")
        }
        viewModel.refresh()
    }

    override fun onStop() {
        super.onStop()
        drag?.let { endDragNow(it) }
    }

    /** Clones a host app or APK into a new engine user in the current group. */
    fun install(source: String) {
        showLoading()
        viewModel.install(source, CloneStore.currentGroup().id)
    }

    private fun showItems(items: List<HomeItem>) {
        val rv = viewBinding.recyclerView
        adapter.submit(items)
        viewBinding.emptyHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        refreshOpenFolder(items)
        if (animateNextItems) {
            animateNextItems = false
            rv.translationX = 0f
            rv.animate().alpha(1f).setDuration(180).start()
            rv.scheduleLayoutAnimation()
        }
    }

    private fun onItemClick(item: HomeItem, view: View) {
        if (drag != null) return
        when (item) {
            is HomeItem.CloneItem -> pressBounce(view) {
                if (openFolder != null) closeFolder()
                showLoading()
                viewModel.launch(item.clone)
            }
            // Opens at once; waiting for the bounce first made the folder feel slow to respond.
            is HomeItem.FolderItem -> {
                pressBounce(view) {}
                openFolder(item, view)
            }
        }
    }

    // ---- groups ----

    private fun showGroupMenu(anchor: View) {
        val groups = CloneStore.groups()
        val currentId = group?.id
        val arrow = (activity as? MainActivity)?.groupTitle()?.groupArrow
        PopupMenu(requireContext(), anchor).apply {
            groups.forEachIndexed { i, g ->
                menu.add(Menu.NONE, i, i, if (g.id == currentId) "✓ ${g.name}" else "    ${g.name}")
            }
            val base = groups.size
            menu.add(Menu.NONE, base, base, R.string.group_new)
            menu.add(Menu.NONE, base + 1, base + 1, R.string.group_rename)
            menu.add(Menu.NONE, base + 2, base + 2, R.string.group_delete)
            setOnMenuItemClickListener { menuItem ->
                when (val id = menuItem.itemId) {
                    in groups.indices -> if (groups[id].id != currentId) switchGroup(groups[id].id)
                    base -> promptName(R.string.group_new, R.string.group_name_hint, "") { name ->
                        switchGroup(CloneStore.createGroup(name))
                    }
                    base + 1 -> group?.let { g ->
                        promptName(R.string.group_rename, R.string.group_name_hint, g.name) { name ->
                            CloneStore.renameGroup(g.id, name)
                            viewModel.refreshLayout()
                        }
                    }
                    base + 2 -> group?.let { deleteGroup(it) }
                }
                true
            }
            setOnDismissListener { arrow?.animate()?.rotation(0f)?.setDuration(180)?.start() }
            arrow?.animate()?.rotation(180f)?.setDuration(180)?.start()
            show()
        }
    }

    private fun switchGroup(groupId: Long) {
        closeFolder(animate = false)
        val rv = viewBinding.recyclerView
        rv.animate().alpha(0f).translationX(-dp(24f)).setDuration(130)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    CloneStore.setCurrentGroup(groupId)
                    animateNextItems = true
                    viewModel.refreshLayout()
                }
                .start()
    }

    private fun deleteGroup(g: CloneStore.Group) {
        when {
            CloneStore.groups().size <= 1 -> toast(R.string.group_delete_last)
            CloneStore.cloneCount(g.id) > 0 || g.items.isNotEmpty() -> toast(R.string.group_delete_not_empty)
            else -> {
                CloneStore.deleteGroup(g.id)
                switchGroup(CloneStore.groups().first().id)
            }
        }
    }

    // ---- folder panel ----

    private fun openFolder(item: HomeItem.FolderItem, source: View) {
        openFolder = item
        backCallback.isEnabled = true
        val panel = viewBinding.folderPanel
        val scrim = viewBinding.folderScrim
        viewBinding.folderTitle.text = item.folder.name
        // Fill the panel before it shows, so no icons are created or animated in while it scales.
        folderAdapter.reset(item.clones)
        panel.animate().cancel()
        scrim.animate().cancel()
        panel.alpha = 0f
        scrim.alpha = 0f
        panel.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        panel.doOnPreDraw {
            setPivotToward(panel, source)
            panel.scaleX = 0.25f
            panel.scaleY = 0.25f
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280)
                    .setInterpolator(DecelerateInterpolator(2f)).withLayer().start()
            scrim.animate().alpha(1f).setDuration(220).start()
        }
    }

    private fun closeFolder(animate: Boolean = true) {
        val folder = openFolder ?: return
        openFolder = null
        backCallback.isEnabled = false
        val panel = viewBinding.folderPanel
        val scrim = viewBinding.folderScrim
        if (!animate) {
            panel.visibility = View.GONE
            scrim.visibility = View.GONE
            return
        }
        viewForKey(viewBinding.recyclerView, adapter, folder.key)?.let { setPivotToward(panel, it) }
        panel.animate().alpha(0f).scaleX(0.25f).scaleY(0.25f).setDuration(200)
                .setInterpolator(AccelerateInterpolator()).withLayer()
                .withEndAction {
                    panel.visibility = View.GONE
                    panel.scaleX = 1f
                    panel.scaleY = 1f
                }
                .start()
        scrim.animate().alpha(0f).setDuration(200).withEndAction { scrim.visibility = View.GONE }.start()
    }

    // Keeps the open folder in step with edits made from inside it.
    private fun refreshOpenFolder(items: List<HomeItem>) {
        val current = openFolder ?: return
        val folder = items.firstOrNull { it is HomeItem.FolderItem && it.folder.id == current.folder.id } as? HomeItem.FolderItem
        if (folder == null) {
            closeFolder()
        } else {
            openFolder = folder
            viewBinding.folderTitle.text = folder.folder.name
            folderAdapter.submit(folder.clones)
        }
    }

    private fun setPivotToward(panel: View, target: View) {
        val (tx, ty) = centreInLayer(target)
        panel.pivotX = tx - panel.left
        panel.pivotY = ty - panel.top
    }

    private fun showFolderMenu(item: HomeItem.FolderItem, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            inflate(R.menu.folder_menu)
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.folder_open -> openFolder(item, anchor)
                    R.id.folder_rename -> renameFolder(item.folder)
                    R.id.folder_move_to_group -> pickOtherGroup { CloneStore.moveToGroup(item.key, it) }
                    R.id.folder_dissolve -> {
                        CloneStore.dissolveFolder(item.folder.id)
                        viewModel.refreshLayout()
                    }
                }
                true
            }
            show()
        }
    }

    private fun renameFolder(folder: CloneStore.Folder) {
        promptName(R.string.folder_rename, R.string.folder_name_hint, folder.name) { name ->
            CloneStore.renameFolder(folder.id, name)
            viewModel.refreshLayout()
        }
    }

    // ---- dragging ----

    override fun isDragging() = drag != null

    private fun startDrag(item: HomeItem, view: View, inFolder: Boolean) {
        if (drag != null) return
        val layer = viewBinding.dragLayer
        val bitmap = try {
            view.drawToBitmap()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot snapshot item: ${e.message}")
            return
        }
        val floating = ImageView(requireContext()).apply {
            setImageBitmap(bitmap)
            elevation = dp(16f)
        }
        val (vx, vy) = positionInLayer(view)
        layer.addView(floating, FrameLayout.LayoutParams(view.width, view.height))
        floating.x = vx
        floating.y = vy
        floating.animate().scaleX(1.12f).scaleY(1.12f).setDuration(150)
                .setInterpolator(DecelerateInterpolator()).start()
        val (lx, ly) = toLayer(layer.lastRawX, layer.lastRawY)
        drag = DragState(item, floating, lx - vx, ly - vy, layer.lastRawX, layer.lastRawY, inFolder)
        adapterFor(inFolder).hiddenKey = item.key
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    override fun onDragMove(rawX: Float, rawY: Float) {
        val d = drag ?: return
        if (d.ending) return
        d.lastX = rawX
        d.lastY = rawY
        val (lx, ly) = toLayer(rawX, rawY)
        d.floating.x = lx - d.offsetX
        d.floating.y = ly - d.offsetY
        if (!d.moved && hypot(rawX - d.startX, rawY - d.startY) > touchSlop()) d.moved = true
        if (!d.moved) return
        if (d.inFolder && !isInside(viewBinding.folderPanel, rawX, rawY)) {
            leaveFolder(d)
        }
        updateAutoScroll(d)
        updateHover(d)
    }

    override fun onDragEnd(rawX: Float, rawY: Float, cancelled: Boolean) {
        val d = drag ?: return
        if (d.ending) return
        d.ending = true
        cancelDwell()
        stopAutoScroll()
        if (!d.moved) {
            settle(d) {
                val anchor = viewForKey(recyclerFor(d.inFolder), adapterFor(d.inFolder), d.item.key)
                finishDrag()
                if (anchor != null && !cancelled) showMenu(d.item, anchor)
            }
            return
        }
        val mergeKey = d.mergeKey
        if (!cancelled && !d.inFolder && mergeKey != null) {
            val target = viewForKey(viewBinding.recyclerView, adapter, mergeKey)
            val groupId = group?.id
            val finish = {
                saveLayout(d)
                if (groupId != null) {
                    CloneStore.merge(groupId, d.item.key, mergeKey, getString(R.string.folder_default_name))
                }
                target?.let { unhighlight(it) }
                finishDrag()
                viewModel.refreshLayout()
            }
            if (target == null) {
                finish()
            } else {
                val (tx, ty) = centreInLayer(target)
                d.floating.animate()
                        .x(tx - d.floating.width / 2f).y(ty - d.floating.height / 2f)
                        .scaleX(0.35f).scaleY(0.35f).alpha(0f).setDuration(220)
                        .setInterpolator(AccelerateInterpolator())
                        .withEndAction(finish)
                        .start()
            }
            return
        }
        settle(d) {
            saveLayout(d)
            finishDrag()
            viewModel.refreshLayout()
        }
    }

    private fun updateHover(d: DragState) {
        val rv = recyclerFor(d.inFolder)
        val ad = adapterFor(d.inFolder)
        val from = ad.items.indexOfFirst { it.key == d.item.key }
        if (from < 0) return
        val (rx, ry) = toView(rv, d.lastX, d.lastY)
        var target = RecyclerView.NO_POSITION
        var nearCentre = false
        val child = rv.findChildViewUnder(rx, ry)
        if (child != null) {
            target = rv.getChildAdapterPosition(child)
            nearCentre = abs(rx - (child.left + child.translationX + child.width / 2f)) < child.width * 0.3f &&
                    abs(ry - (child.top + child.translationY + child.height / 2f)) < child.height * 0.3f
        } else if (rx in 0f..rv.width.toFloat() && ry in 0f..rv.height.toFloat()) {
            // Past the last icon: move to the end.
            val last = rv.getChildAt(rv.childCount - 1)
            if (last != null && (ry > last.bottom || (ry > last.top && rx > last.right))) target = ad.itemCount - 1
        }
        if (target == RecyclerView.NO_POSITION || target == from) {
            cancelDwell()
            d.pendingPos = -1
            if (target == from) clearMerge(d)
            return
        }
        val merge = !d.inFolder && nearCentre && d.item is HomeItem.CloneItem
        if (target == d.pendingPos && merge == d.pendingMerge) return
        cancelDwell()
        if (!merge || ad.items.getOrNull(target)?.key != d.mergeKey) clearMerge(d)
        d.pendingPos = target
        d.pendingMerge = merge
        val action = Runnable {
            dwell = null
            if (drag !== d) return@Runnable
            val key = ad.items.getOrNull(target)?.key ?: return@Runnable
            if (merge) {
                d.mergeKey = key
                viewForKey(rv, ad, key)?.let { highlight(it) }
            } else {
                ad.move(ad.items.indexOfFirst { it.key == d.item.key }, target)
                d.pendingPos = -1
            }
        }
        dwell = action
        handler.postDelayed(action, if (merge) 220L else 130L)
    }

    private fun leaveFolder(d: DragState) {
        val folder = openFolder ?: return
        cancelDwell()
        val index = folderAdapter.items.indexOfFirst { it.key == d.item.key }
        folderAdapter.hiddenKey = null
        folderAdapter.remove(index)
        val remaining = folderAdapter.items.filterIsInstance<HomeItem.CloneItem>()
        closeFolder()
        d.inFolder = false
        d.leftFolderId = folder.folder.id
        d.pendingPos = -1
        adapter.hiddenKey = d.item.key
        val folderIndex = adapter.items.indexOfFirst { it.key == folder.key }
        if (folderIndex < 0) {
            adapter.insert(adapter.itemCount, d.item)
        } else if (remaining.isEmpty()) {
            adapter.replace(folderIndex, d.item)
        } else if (remaining.size == 1) {
            // The folder dissolves; its last clone takes its place.
            adapter.replace(folderIndex, remaining[0])
            adapter.insert(folderIndex + 1, d.item)
        } else {
            adapter.replace(folderIndex, folder.copy(clones = remaining))
            adapter.insert(folderIndex + 1, d.item)
        }
    }

    /** Animates the dragged icon back into its slot, then runs [end]. */
    private fun settle(d: DragState, end: () -> Unit) {
        val slot = viewForKey(recyclerFor(d.inFolder), adapterFor(d.inFolder), d.item.key)
        val animator = d.floating.animate().scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(DecelerateInterpolator())
        if (slot != null) {
            val (sx, sy) = positionInLayer(slot)
            animator.x(sx).y(sy)
        } else {
            animator.alpha(0f)
        }
        animator.withEndAction(end).start()
    }

    private fun saveLayout(d: DragState) {
        val g = group ?: return
        val folders = mutableMapOf<Long, List<String>>()
        adapter.items.filterIsInstance<HomeItem.FolderItem>().forEach { f ->
            folders[f.folder.id] = f.clones.map { it.key }
        }
        if (d.inFolder) openFolder?.let { folders[it.folder.id] = folderAdapter.items.map { item -> item.key } }
        d.leftFolderId?.let { if (it !in folders) folders[it] = emptyList() }
        CloneStore.applyLayout(g.id, adapter.items.map { it.key }, folders)
    }

    private fun finishDrag() {
        val d = drag ?: return
        drag = null
        viewBinding.dragLayer.removeView(d.floating)
        adapter.hiddenKey = null
        folderAdapter.hiddenKey = null
        pendingItems?.let {
            pendingItems = null
            showItems(it)
        }
    }

    // Leaving the screen mid-drag: keep what the user arranged so far, without animation.
    private fun endDragNow(d: DragState) {
        cancelDwell()
        stopAutoScroll()
        d.mergeKey?.let { key -> viewForKey(viewBinding.recyclerView, adapter, key)?.let { unhighlight(it) } }
        if (d.moved) saveLayout(d)
        finishDrag()
    }

    private fun highlight(view: View) {
        HomeAdapter.setDropTarget(view, true)
        view.animate().scaleX(1.12f).scaleY(1.12f).setDuration(150).start()
    }

    private fun unhighlight(view: View) {
        HomeAdapter.setDropTarget(view, false)
        view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
    }

    private fun clearMerge(d: DragState) {
        val key = d.mergeKey ?: return
        d.mergeKey = null
        viewForKey(viewBinding.recyclerView, adapter, key)?.let { unhighlight(it) }
    }

    private fun cancelDwell() {
        dwell?.let { handler.removeCallbacks(it) }
        dwell = null
    }

    private fun updateAutoScroll(d: DragState) {
        if (d.inFolder) {
            stopAutoScroll()
            return
        }
        val rv = viewBinding.recyclerView
        val (_, ry) = toView(rv, d.lastX, d.lastY)
        val edge = dp(72f)
        val speed = when {
            ry < edge -> -dp(10f)
            ry > rv.height - edge -> dp(10f)
            else -> 0f
        }
        if (speed == 0f) {
            stopAutoScroll()
            return
        }
        if (autoScroll != null) return
        val step = object : Runnable {
            override fun run() {
                val current = drag ?: return
                val (_, y) = toView(rv, current.lastX, current.lastY)
                val s = when {
                    y < edge -> -dp(10f)
                    y > rv.height - edge -> dp(10f)
                    else -> 0f
                }
                if (s == 0f) {
                    autoScroll = null
                    return
                }
                rv.scrollBy(0, s.toInt())
                updateHover(current)
                handler.postDelayed(this, 16)
            }
        }
        autoScroll = step
        handler.post(step)
    }

    private fun stopAutoScroll() {
        autoScroll?.let { handler.removeCallbacks(it) }
        autoScroll = null
    }

    // ---- clone menu ----

    private fun showMenu(item: HomeItem, anchor: View) {
        when (item) {
            is HomeItem.CloneItem -> showCloneMenu(item, anchor)
            is HomeItem.FolderItem -> showFolderMenu(item, anchor)
        }
    }

    private fun showCloneMenu(item: HomeItem.CloneItem, anchor: View) {
        val clone = item.clone
        val inFolder = CloneStore.folderOf(clone.key) != null
        PopupMenu(requireContext(), anchor).apply {
            inflate(R.menu.clone_menu)
            if (inFolder) menu.removeItem(R.id.clone_move_to_folder) else menu.removeItem(R.id.clone_move_out)
            if (!AppFreezeManager.isGlobalAutoFreezeEnabled()) menu.removeItem(R.id.app_freeze_options)
            if (clone.packageName != SHEALTH_PACKAGE) {
                menu.removeItem(R.id.app_shealth_login_fallback)
            } else {
                val enabled = SamsungHealthCompat.isHostSamsungAccountFallbackEnabled(clone.userId, clone.packageName)
                menu.findItem(R.id.app_shealth_login_fallback)?.setTitle(
                        if (enabled) R.string.shealth_login_fallback_disable else R.string.shealth_login_fallback_enable
                )
            }
            setOnMenuItemClickListener { menuItem ->
                try {
                    onCloneMenuItem(menuItem.itemId, item)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in clone menu: ${e.message}")
                }
                true
            }
            show()
        }
    }

    private fun onCloneMenuItem(itemId: Int, item: HomeItem.CloneItem) {
        val clone = item.clone
        when (itemId) {
            R.id.clone_rename -> promptName(R.string.clone_rename, R.string.clone_rename_hint, item.name) { name ->
                CloneStore.renameClone(clone.key, name)
                viewModel.refreshLayout()
            }
            R.id.clone_device -> DeviceSpoofingActivity.start(requireContext(), clone.userId)
            R.id.clone_location -> startActivity(
                    Intent(requireContext(), FakeManagerActivity::class.java).putExtra("userID", clone.userId)
            )
            R.id.clone_gallery -> BlackBoxGalleryActivity.start(requireContext(), clone.userId)
            R.id.clone_move_to_folder -> pickFolder(clone)
            R.id.clone_move_out -> {
                CloneStore.moveOutOfFolder(clone.key)
                viewModel.refreshLayout()
            }
            R.id.clone_move_to_group -> pickOtherGroup { CloneStore.moveToGroup(clone.key, it) }
            R.id.app_shortcut -> ShortcutUtil.createShortcut(
                    requireContext(), clone.userId, AppInfo(item.name, item.icon, clone.packageName, "", false)
            )
            R.id.app_stop -> confirm(R.string.app_stop, getString(R.string.app_stop_hint, item.name)) {
                BlackBoxCore.get().stopPackage(clone.packageName, clone.userId)
                toast(getString(R.string.is_stop, item.name))
            }
            R.id.app_clear -> confirm(R.string.app_clear, getString(R.string.app_clear_hint, item.name)) {
                showLoading()
                viewModel.clearData(clone)
            }
            R.id.app_freeze_options -> showFreezeOptions(item)
            R.id.app_shealth_login_fallback -> toggleSamsungHealthLoginFallback(clone)
            R.id.app_remove -> confirm(R.string.clone_delete, getString(R.string.clone_delete_hint, item.name)) {
                closeFolder()
                showLoading()
                viewModel.delete(clone)
            }
        }
    }

    private fun pickFolder(clone: CloneStore.Clone) {
        val g = group ?: return
        val folders = g.items.filter { CloneStore.isFolderKey(it) }.mapNotNull { CloneStore.folder(it.drop(2).toLong()) }
        if (folders.isEmpty()) {
            // A folder needs two clones, so it is made by dropping one clone onto another.
            toast(R.string.folder_create_hint)
            return
        }
        AlertDialog.Builder(requireContext())
                .setTitle(R.string.folder_pick)
                .setItems(folders.map { it.name }.toTypedArray()) { _, which ->
                    CloneStore.moveToFolder(clone.key, folders[which].id)
                    viewModel.refreshLayout()
                }
                .show()
    }

    private fun pickOtherGroup(onPicked: (Long) -> Unit) {
        val others = CloneStore.groups().filter { it.id != group?.id }
        if (others.isEmpty()) {
            toast(R.string.no_other_group)
            return
        }
        AlertDialog.Builder(requireContext())
                .setTitle(R.string.group_pick)
                .setItems(others.map { it.name }.toTypedArray()) { _, which ->
                    closeFolder()
                    onPicked(others[which].id)
                    viewModel.refreshLayout()
                }
                .show()
    }

    private fun showFreezeOptions(item: HomeItem.CloneItem) {
        val clone = item.clone
        AlertDialog.Builder(requireContext())
                .setTitle(R.string.app_freeze_options)
                .setMessage(getString(R.string.app_freeze_options_hint, item.name))
                .setPositiveButton(R.string.enable) { _, _ ->
                    AppFreezeManager.setExcludedFromAutoFreeze(clone.packageName, clone.userId, true)
                    toast(R.string.app_excluded_from_auto_freeze_enabled)
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    AppFreezeManager.setExcludedFromAutoFreeze(clone.packageName, clone.userId, false)
                    toast(R.string.app_excluded_from_auto_freeze_disabled)
                }
                .setNeutralButton(R.string.app_freeze_now) { _, _ ->
                    BlackBoxCore.get().stopPackage(clone.packageName, clone.userId)
                    toast(getString(R.string.app_is_frozen, item.name))
                }
                .show()
    }

    private fun toggleSamsungHealthLoginFallback(clone: CloneStore.Clone) {
        val enabled = SamsungHealthCompat.isHostSamsungAccountFallbackEnabled(clone.userId, clone.packageName)
        if (enabled) {
            SamsungHealthCompat.setHostSamsungAccountFallbackEnabled(clone.userId, clone.packageName, false)
            toast(R.string.shealth_login_fallback_disabled)
            return
        }
        AlertDialog.Builder(requireContext())
                .setTitle(R.string.shealth_login_fallback_dialog_title)
                .setMessage(R.string.shealth_login_fallback_dialog_message)
                .setPositiveButton(R.string.enable) { _, _ ->
                    SamsungHealthCompat.setHostSamsungAccountFallbackEnabled(clone.userId, clone.packageName, true)
                    toast(R.string.shealth_login_fallback_enabled)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    // ---- helpers ----

    private fun promptName(titleRes: Int, hintRes: Int, prefill: String, onDone: (String) -> Unit) {
        MaterialDialog(requireContext()).show {
            title(titleRes)
            input(hintRes = hintRes, prefill = prefill, allowEmpty = false) { _, text ->
                val name = text.toString().trim()
                if (name.isNotEmpty()) onDone(name)
            }
            positiveButton(R.string.done)
            negativeButton(R.string.cancel)
        }
    }

    private fun confirm(titleRes: Int, message: String, onConfirm: () -> Unit) {
        MaterialDialog(requireContext()).show {
            title(titleRes)
            message(text = message)
            positiveButton(R.string.done) { onConfirm() }
            negativeButton(R.string.cancel)
        }
    }

    private fun pressBounce(view: View, then: () -> Unit) {
        view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(260)
                            .setInterpolator(OvershootInterpolator(2.5f)).start()
                    then()
                }
                .start()
    }

    private fun adapterFor(inFolder: Boolean) = if (inFolder) folderAdapter else adapter

    private fun recyclerFor(inFolder: Boolean): RecyclerView =
            if (inFolder) viewBinding.folderRecycler else viewBinding.recyclerView

    private fun viewForKey(rv: RecyclerView, ad: HomeAdapter, key: String): View? {
        val index = ad.items.indexOfFirst { it.key == key }
        if (index < 0) return null
        return rv.findViewHolderForAdapterPosition(index)?.itemView
    }

    private fun toLayer(rawX: Float, rawY: Float): Pair<Float, Float> {
        val loc = IntArray(2)
        viewBinding.dragLayer.getLocationOnScreen(loc)
        return Pair(rawX - loc[0], rawY - loc[1])
    }

    private fun toView(view: View, rawX: Float, rawY: Float): Pair<Float, Float> {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Pair(rawX - loc[0], rawY - loc[1])
    }

    // Untransformed top-left of a view in drag layer coordinates.
    private fun positionInLayer(view: View): Pair<Float, Float> {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val layer = IntArray(2)
        viewBinding.dragLayer.getLocationOnScreen(layer)
        // getLocationOnScreen includes the view's own scale; undo it around the centre.
        val dx = view.width * (1 - view.scaleX) / 2f
        val dy = view.height * (1 - view.scaleY) / 2f
        return Pair(loc[0] - layer[0] - dx, loc[1] - layer[1] - dy)
    }

    private fun centreInLayer(view: View): Pair<Float, Float> {
        val (x, y) = positionInLayer(view)
        return Pair(x + view.width / 2f, y + view.height / 2f)
    }

    private fun isInside(view: View, rawX: Float, rawY: Float): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX <= loc[0] + view.width * view.scaleX &&
                rawY >= loc[1] && rawY <= loc[1] + view.height * view.scaleY
    }

    private fun touchSlop() = ViewConfiguration.get(requireContext()).scaledTouchSlop * 1.5f

    private fun dp(value: Float) = Resolution.convertDpToPixel(value, requireContext())

    private fun showLoading() {
        (activity as? LoadingActivity)?.showLoading()
    }

    private fun hideLoading() {
        (activity as? LoadingActivity)?.hideLoading()
    }

    companion object {
        private const val TAG = "HomeFragment"
        private const val SHEALTH_PACKAGE = "com.sec.android.app.shealth"
        private const val PREWARMED_FOLDER_VIEWS = 12
    }
}
