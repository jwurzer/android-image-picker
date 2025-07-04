package com.esafirm.imagepicker.features

import android.Manifest
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.esafirm.imagepicker.R
import com.esafirm.imagepicker.databinding.EfFragmentImagePickerBinding
import com.esafirm.imagepicker.features.camera.CameraHelper.checkCameraAvailability
import com.esafirm.imagepicker.features.fileloader.DefaultImageFileLoader
import com.esafirm.imagepicker.features.recyclers.RecyclerViewManager
import com.esafirm.imagepicker.helper.ConfigUtils
import com.esafirm.imagepicker.helper.ImagePickerPreferences
import com.esafirm.imagepicker.helper.ImagePickerUtils
import com.esafirm.imagepicker.helper.IpLogger
import com.esafirm.imagepicker.helper.state.fetch
import com.esafirm.imagepicker.model.Folder
import com.esafirm.imagepicker.model.Image

class ImagePickerFragment : Fragment() {

    private var binding: EfFragmentImagePickerBinding? = null
    private lateinit var recyclerViewManager: RecyclerViewManager

    private val preferences: ImagePickerPreferences by lazy {
        ImagePickerPreferences(requireContext())
    }

    private val config: ImagePickerConfig by lazy {
        requireArguments().getParcelable(ImagePickerConfig::class.java.simpleName)!!
    }

    private val permissions: Array<String> by lazy {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE->
                arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(READ_MEDIA_IMAGES)

            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || Environment.isExternalStorageLegacy() -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(RequestMultiplePermissions()) { resultMap ->
            if (resultMap.values.any { it }) {
                IpLogger.d("Write External permission granted")
                loadData()
            } else {
                IpLogger.e("Permission not granted")
                interactionListener.cancel()
            }
        }

    private lateinit var presenter: ImagePickerPresenter
    private lateinit var interactionListener: ImagePickerInteractionListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(
            ContentObserverTrigger(
                requireActivity().contentResolver,
                this::loadData
            )
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        presenter = ImagePickerPresenter(DefaultImageFileLoader(requireContext()))

        if (::interactionListener.isInitialized.not()) {
            throw RuntimeException(
                "ImagePickerFragment needs an " +
                    "ImagePickerInteractionListener. This will be set automatically if the " +
                    "activity implements ImagePickerInteractionListener, and can be set manually " +
                    "with fragment.setInteractionListener(listener)."
            )
        }

        val interactionListener = this.interactionListener

        // clone the inflater using the ContextThemeWrapper
        val localInflater = inflater.cloneInContext(ContextThemeWrapper(activity, config.theme))

        // inflate the layout using the cloned inflater, not default inflater
        val view = localInflater.inflate(R.layout.ef_fragment_image_picker, container, false)
        val viewBinding = EfFragmentImagePickerBinding.bind(view)

        val selectedImages = if (savedInstanceState == null) {
            config.selectedImages
        } else {
            savedInstanceState.getParcelableArrayList(STATE_KEY_SELECTED_IMAGES)
        }

        val recyclerViewManager = createRecyclerViewManager(
            recyclerView = viewBinding.recyclerView,
            config = config,
            passedSelectedImages = selectedImages ?: emptyList(),
            interactionListener = interactionListener
        )

        if (savedInstanceState != null) {
            recyclerViewManager.onRestoreState(savedInstanceState.getParcelable(STATE_KEY_RECYCLER))
        }

        interactionListener.selectionChanged(recyclerViewManager.selectedImages)

        // Assignment for property
        this.binding = viewBinding
        this.recyclerViewManager = recyclerViewManager

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        subscribeToUiState()
    }

    private fun subscribeToUiState() = presenter.getUiState().observe(this) { state ->
        showLoading(state.isLoading)

        state.error.fetch {
            showError(this)
        }

        val isEmpty = state.images.isEmpty()
        if (isEmpty && state.isLoading.not()) {
            showEmpty()
            return@observe
        }

        state.isFolder.fetch {
            val isFolderMode = this
            if (isFolderMode) {
                setFolderAdapter(state.folders)
            } else {
                setImageAdapter(state.images)
            }
        }

        state.finishPickImage.fetch {
            val images = this
            interactionListener.finishPickImages(
                ImagePickerUtils.createResultIntent(images)
            )
        }

        state.showCapturedImage.fetch {
            loadDataWithPermission()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun createRecyclerViewManager(
        recyclerView: RecyclerView,
        config: ImagePickerConfig,
        passedSelectedImages: List<Image>,
        interactionListener: ImagePickerInteractionListener
    ) = RecyclerViewManager(
        recyclerView,
        config,
        resources.configuration.orientation
    ).apply {
        val selectListener = { isSelected: Boolean -> selectImage(isSelected) }
        val folderClick = { bucket: Folder ->
            setImageAdapter(bucket.images)
            updateTitle()
        }

        setupAdapters(passedSelectedImages, selectListener, folderClick)
        setImageSelectedListener { selectedImages ->
            updateTitle()
            interactionListener.selectionChanged(selectedImages)
            if (ConfigUtils.shouldReturn(config, false) && selectedImages.isNotEmpty()) {
                onDone()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadDataWithPermission()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(STATE_KEY_RECYCLER, recyclerViewManager.recyclerState)
        outState.putParcelableArrayList(
            STATE_KEY_SELECTED_IMAGES,
            recyclerViewManager.selectedImages as ArrayList<out Parcelable?>
        )
    }

    /**
     * Set image adapter
     * 1. Set new data
     * 2. Update item decoration
     * 3. Update title
     */
    private fun setImageAdapter(images: List<Image>) {
        recyclerViewManager.setImageAdapter(images)
        updateTitle()
    }

    private fun setFolderAdapter(folders: List<Folder>) {
        recyclerViewManager.setFolderAdapter(folders)
        updateTitle()
    }

    private fun updateTitle() {
        interactionListener.setTitle(recyclerViewManager.title)
    }

    /**
     * On finish selected image
     * Get all selected images then return image to caller activity
     */
    fun onDone() {
        presenter.onDoneSelectImages(recyclerViewManager.selectedImages, config)
    }

    /**
     * Config recyclerView when configuration changed
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // recyclerViewManager can be null here if we use cameraOnly mode
        recyclerViewManager.changeOrientation(newConfig.orientation)
    }

    /**
     * Check permission
     */
    private fun loadDataWithPermission() {
        checkPermission()
        val isGranted = permissions.any {
            ActivityCompat.checkSelfPermission(requireContext(), it) == PERMISSION_GRANTED
        }
        if (isGranted) {
            loadData()
        } else {
            requestWriteExternalOrReadImagesPermission()
        }
    }

    private fun checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && (ContextCompat.checkSelfPermission(requireContext(), READ_MEDIA_IMAGES) == PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(requireContext(), READ_MEDIA_VIDEO) == PERMISSION_GRANTED)
        ) {
            // Full access on Android 13 (API level 33) or higher
            binding?.rlHeader?.visibility = View.GONE
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(requireContext(), READ_MEDIA_VISUAL_USER_SELECTED) == PERMISSION_GRANTED
        ) {
            // Partial access on Android 14 (API level 34) or higher
            binding?.let {
                it.tvHeader.text = getString(R.string.ef_media_status_piece)
                it.btnManage.text = getString(R.string.ef_manage)
                it.rlHeader.visibility = View.VISIBLE
                it.btnManage.setOnClickListener {
                    requestPermissionLauncher.launch(arrayOf(READ_MEDIA_VISUAL_USER_SELECTED, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO))
                }
            }
        } else {
            binding?.rlHeader?.visibility = View.GONE
        }
    }


    private fun loadData() = presenter.loadData(config)

    /**
     * Request for permission
     * If permission denied or app is first launched, request for permission
     * If permission denied and user choose 'Never Ask Again', show snackbar with an action that navigate to app settings
     */
    private fun requestWriteExternalOrReadImagesPermission() {
        IpLogger.w("Write External permission or Read Media Images is not granted. Requesting permission")

        val shouldProvideRationale = permissions.any { permission -> shouldShowRequestPermissionRationale(permission) }
        if (shouldProvideRationale) {
            requestPermissionLauncher.launch(permissions)
            return
        }

        if (ActivityCompat.checkSelfPermission(requireContext(),READ_MEDIA_VISUAL_USER_SELECTED) == PERMISSION_GRANTED){
            return
        }

        if (!preferences.isPermissionRequested()) {
            preferences.setPermissionIsRequested()
            requestPermissionLauncher.launch(permissions)
        } else {
            binding?.efSnackbar?.show(R.string.ef_msg_no_write_external_permission) {
                openAppSettings()
            }
        }
    }

    /**
     * Open app settings screen
     */
    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", requireActivity().packageName, null)
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /**
     * Check if the captured image is stored successfully
     * Then reload data
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_CAPTURE) {
            if (resultCode == Activity.RESULT_OK) {
                presenter.finishCaptureImage(requireContext(), data, config)
                Handler(Looper.getMainLooper()).postDelayed({
                    binding?.recyclerView?.scrollToPosition(0)
                }, 500)
            } else if (resultCode == Activity.RESULT_CANCELED) {
                presenter.abortCaptureImage(requireContext())
            }
        }
    }

    /**
     * Start camera intent
     * Create a temporary file and pass file Uri to camera intent
     */
    fun captureImage() {
        if (!checkCameraAvailability(requireActivity())) {
            return
        }
        presenter.captureImage(this, config, RC_CAPTURE)
    }

    override fun onDestroy() {
        super.onDestroy()
        presenter.abortLoad()
    }

    /**
     * @return true if the [Fragment] consume the back event
     */
    fun handleBack(): Boolean {
        if (recyclerViewManager.handleBack()) {
            // Handled.
            updateTitle()
            return true
        }
        return false
    }

    val isShowDoneButton: Boolean
        get() = recyclerViewManager.isShowDoneButton

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is ImagePickerInteractionListener) {
            setInteractionListener(context)
        }
    }

    fun setInteractionListener(listener: ImagePickerInteractionListener) {
        interactionListener = listener
    }

    private fun showError(throwable: Throwable?) {
        var message = "Unknown Error"
        if (throwable is NullPointerException) {
            message = "Images do not exist"
        }
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun showLoading(isLoading: Boolean) {
        binding?.run {
            progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            recyclerView.visibility = if (isLoading) View.GONE else View.VISIBLE
            tvEmptyImages.visibility = View.GONE
        }
    }

    private fun showEmpty() {
        binding?.run {
            progressBar.visibility = View.GONE
            recyclerView.visibility = View.GONE
            tvEmptyImages.visibility = View.VISIBLE
        }
    }

    companion object {
        private const val STATE_KEY_RECYCLER = "Key.Recycler"
        private const val STATE_KEY_SELECTED_IMAGES = "Key.SelectedImages"

        private const val RC_CAPTURE = 2000

        fun newInstance(config: ImagePickerConfig): ImagePickerFragment {
            val args = Bundle().apply {
                putParcelable(ImagePickerConfig::class.java.simpleName, config)
            }
            return ImagePickerFragment().apply {
                arguments = args
            }
        }
    }
}
