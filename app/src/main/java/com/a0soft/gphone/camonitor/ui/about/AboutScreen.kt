package com.a0soft.gphone.camonitor.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.a0soft.gphone.camonitor.BuildConfig
import com.a0soft.gphone.camonitor.R
import com.a0soft.gphone.camonitor.data.update.ReleaseCheckResult
import com.a0soft.gphone.camonitor.data.update.ReleaseChecker
import com.a0soft.gphone.camonitor.data.update.ReleaseInfo
import com.a0soft.gphone.camonitor.data.update.UpdatePreferenceManager
import com.a0soft.gphone.camonitor.ui.home.UpdateBannerCard
import kotlinx.coroutines.launch

/**
 * AI AGENT INSTRUCTION / MAINTENANCE PROMPT:
 * -----------------------------------------------------------------------------------------
 * When new PRs are merged or new contributors contribute to CamMonitor:
 *
 * 1. HOW TO RETRIEVE CONTRIBUTOR INFORMATION:
 *    - Run: `git log --format='%aN <%aE>' | sort -u` or inspect merged GitHub PR metadata.
 *    - Identify the contributor's display name, GitHub username/handle (e.g., "@username"),
 *      GitHub profile URL (`https://github.com/username`), and their primary contribution domain.
 *
 * 2. REQUIRED FILES TO UPDATE:
 *    a. [AboutScreen.kt]:
 *       - Append a new [ContributorInfo] entry to [CONTRIBUTORS] below.
 *    b. String Resource Files (MUST update all 4 locales for parity):
 *       - `res/values/strings.xml` (Default/English): Define `about_contributor_<id>_role`
 *       - `res/values-b+zh+Hant/strings.xml` (Traditional Chinese)
 *       - `res/values-b+zh+Hans/strings.xml` (Simplified Chinese)
 *       - `res/values-ja/strings.xml` (Japanese)
 *    c. [Specification.md]:
 *       - Update Section 5.7 ("About Screen" -> "Contributors Card") with the new entry.
 *
 * 3. VERIFICATION:
 *    - Run `./gradlew testDebugUnitTest` to ensure `LocaleResourcesTest` passes without missing keys.
 * -----------------------------------------------------------------------------------------
 */
private data class ContributorInfo(
    val name: String,
    val handle: String,
    val roleResId: Int,
    val githubUrl: String
)

private val CONTRIBUTORS = listOf(
    ContributorInfo(
        name = "samlu",
        handle = "@samlu",
        roleResId = R.string.about_contributor_samlu_role,
        githubUrl = "https://github.com/samlu"
    ),
    ContributorInfo(
        name = "OWWhale",
        handle = "@OWWhale",
        roleResId = R.string.about_contributor_owwhale_role,
        githubUrl = "https://github.com/OWWhale"
    ),
    ContributorInfo(
        name = "Ikko Eltociear Ashimine",
        handle = "@eltociear",
        roleResId = R.string.about_contributor_eltociear_role,
        githubUrl = "https://github.com/eltociear"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val githubUrl = stringResource(R.string.about_github_url)
    val scope = rememberCoroutineScope()
    val updatePrefs = remember { UpdatePreferenceManager(context) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateStatusMessage by remember { mutableStateOf<String?>(null) }
    var availableRelease by remember { mutableStateOf<ReleaseInfo?>(null) }

    LaunchedEffect(Unit) {
        val cached = updatePrefs.getCachedRelease()
        if (cached != null && !updatePrefs.isVersionSkipped(cached.versionName)) {
            val eval = ReleaseChecker.evaluateRelease(cached, BuildConfig.VERSION_NAME)
            if (eval is ReleaseCheckResult.UpdateAvailable) {
                availableRelease = cached
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // App Icon
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(24.dp))
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_background),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // App Name
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Version Number
            Text(
                text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Check for Updates Button
            OutlinedButton(
                onClick = {
                    if (isCheckingUpdate) return@OutlinedButton
                    isCheckingUpdate = true
                    updateStatusMessage = null
                    availableRelease = null
                    scope.launch {
                        val result = ReleaseChecker.checkLatestRelease()
                        isCheckingUpdate = false
                        when (result) {
                            is ReleaseCheckResult.UpdateAvailable -> {
                                updatePrefs.saveCachedRelease(result.release)
                                availableRelease = result.release
                                updateStatusMessage = null
                            }
                            is ReleaseCheckResult.UpToDate -> {
                                updateStatusMessage = context.getString(R.string.update_status_latest, BuildConfig.VERSION_NAME)
                            }
                            is ReleaseCheckResult.Error -> {
                                updateStatusMessage = context.getString(R.string.update_status_failed)
                            }
                        }
                    }
                },
                enabled = !isCheckingUpdate
            ) {
                if (isCheckingUpdate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.update_check_manual))
            }

            updateStatusMessage?.let { msg ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            availableRelease?.let { release ->
                Spacer(modifier = Modifier.height(12.dp))
                UpdateBannerCard(
                    release = release,
                    onViewUpdate = { url ->
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    },
                    onSkipVersion = { version ->
                        updatePrefs.skipVersion(version)
                        availableRelease = null
                    },
                    onDismiss = {
                        availableRelease = null
                    }
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Info Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // App Description
                    Text(
                        text = stringResource(R.string.about_description),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    // Open Source Notice
                    Text(
                        text = stringResource(R.string.about_open_source_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // GitHub Link Button
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.about_view_on_github))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = githubUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Contributors Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Groups,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.about_contributors_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_contributors_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    CONTRIBUTORS.forEachIndexed { index, contributor ->
                        if (index > 0) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(contributor.githubUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = contributor.name.take(1).uppercase(),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = contributor.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = contributor.handle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(contributor.roleResId),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val contributorsGraphUrl = stringResource(R.string.about_contributors_github_url)
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(contributorsGraphUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.about_view_all_contributors))
                    }
                }
            }
        }
    }
}
