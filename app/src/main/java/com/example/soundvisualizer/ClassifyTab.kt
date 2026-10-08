package com.example.soundvisualizer

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** 줄 배경 위에 올리는 칸(종류 버튼, 거르기 칸)의 색. 설정 탭의 고르지 않은 모드 칸과 같다. */
private val ChipColor = Color(0xFF333A44)

/** 분류 탭에서 고를 수 있는 종류. 드롭다운과 거르기 칸이 이 순서로 보인다. */
private val SOUND_TYPES = listOf(AiClassification.AMBIENT, AiClassification.SPEECH, AiClassification.DANGER)

/** 목록에서 붙어 있는 찾기·거르기 줄의 자리. 그 앞은 제목·설명·고급 모드 스위치를 담은 칸 하나뿐이다. */
private const val STICKY_HEADER_INDEX = 1

/** 줄 오른쪽 종류 버튼의 최대 폭. 흔한 폭(411dp)에서 줄 안쪽의 절반쯤이다. */
private val TYPE_BUTTON_MAX_WIDTH = 150.dp

/** 종류 목록을 여는 줄의 최소 높이. 손가락과 화면 읽어주기로 누르기 좋은 크기(48dp)다. */
private val MIN_TOUCH_HEIGHT = 48.dp

/** 화면 읽어주기가 읽는 미리보기의 이름 사이. 화면에 보이는 " • " 는 기호라 읽히지 않게 쉼표로 바꾼다. */
private const val SPOKEN_SEPARATOR = ", "

/**
 * 분류 탭. AI 가 알아듣는 소리를 어느 종류(환경음·대화음·위협음)로 볼지 보여 주고 바꾸게 한다(#349).
 *
 * - **기본 모드**: 비슷한 소리를 묶은 카드에서 묶음째 고른다. 카드는 소리마다의 기본 종류 구역(위협음 → 대화음 →
 *   환경음)에 놓이고, 바꿔도 제 구역에 그대로 있다. 고르면 [SettingsManager.setSoundTypes] 가 찾는 말·거르기와
 *   상관없이 카드의 소리를 모두 한 번에 바꾼다. 소리마다 종류가 다른 카드는 바꾸기 전에 묻는다.
 * - **고급 모드**: 같은 순서로 묶음 머리 밑에 소리를 한 줄씩 펴서 하나씩 바꾼다([SettingsManager.setSoundType]).
 *   켜 둔 것은 [SettingsManager.classifyAdvanced] 에 남는다. 하나씩 바꾼 묶음이 있는 채로 끄면 먼저 알린다.
 *
 * 저장하는 것은 소리마다의 종류([SettingsManager.soundTypes])뿐이고, 묶음의 상태는 그때그때 정한다([SoundGroups]).
 * AI 는 추론마다 그 값을 읽어 판정에 반영한다(#321).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClassifyTab() {
    val context = LocalContext.current
    // 소리 목록은 파일에서 읽으므로 메인 스레드 밖에서 읽는다. 한 번 읽으면 들고 있어 다시 들어올 때는 바로 그린다.
    val entries by produceState(SoundCatalog.cachedOrNull()) {
        if (value == null) value = withContext(Dispatchers.IO) { SoundCatalog.load(context.applicationContext) }
    }
    // 화면에 보이는 이름은 앱 언어로 번역된 배열이다. 글자를 칠 때마다 521개를 다시 읽지 않게 붙들어 두고,
    // 언어가 바뀌어 리소스가 바뀔 때만 다시 읽는다.
    val resources = LocalResources.current
    val labels = remember(resources) { resources.getStringArray(R.array.sound_names) }
    val overrides by SettingsManager.soundTypes.collectAsState()
    val advanced by SettingsManager.classifyAdvanced.collectAsState()

    // 찾는 말은 입력 칸이 직접 들고 있게 한다. 값을 받아 다시 넘겨주는 방식(value/onValueChange)은 목록이 다시 그려지는
    // 동안 입력이 몰리면 글자가 빠졌다(에뮬레이터에서 "bell" 이 "bll" 로 들어감).
    val search = rememberTextFieldState()
    val query = search.text.toString()
    var filter by rememberSaveable { mutableStateOf(SoundFilter.ALL) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    // 하나씩 바꾼 묶음이 있는 채로 고급 모드를 끌 때 띄우는 알림. 화면을 돌려도 떠 있게 저장한다.
    var confirmBasic by rememberSaveable { mutableStateOf(false) }
    // 소리마다 종류가 다른 카드에서 고른 것(카드 id, 종류). 묶음 전체를 바꿀지 묻는 동안만 있다.
    var pendingPick by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }

    val typeColors = mapOf(
        AiClassification.AMBIENT to Color(SettingsManager.colorAmbient.collectAsState().value),
        AiClassification.SPEECH to Color(SettingsManager.colorSpeech.collectAsState().value),
        AiClassification.DANGER to Color(SettingsManager.colorDanger.collectAsState().value)
    )

    val list = entries
    val labelOf = { entry: SoundEntry -> labels.getOrElse(entry.index) { entry.name } }
    val searchKeys = remember(list, labels) { list?.map { SoundCatalog.searchKey(labelOf(it), it.name) }.orEmpty() }
    // 묶음과 구역은 목록을 읽으면 한 번만 나눈다. 글자를 칠 때마다 다시 하는 것은 아래의 거르기(521개)뿐이다.
    val sections = remember(list) { list?.let(SoundGroups::sections).orEmpty() }
    val titleKeys = remember(resources) { groupTitleKeys(context, resources) }
    val shown = remember(sections, searchKeys, titleKeys, overrides, query, filter) {
        SoundGroups.filter(sections, searchKeys, titleKeys, overrides, query, filter)
    }
    val advancedItems = remember(shown, advanced) { if (advanced) SoundGroups.advancedItems(shown) else emptyList() }
    // 저장값에는 지금 목록에 없는 이름이 남아 있을 수 있다(모델의 이름이 바뀐 뒤 등). "바꾼 소리" 거르기와 같은 기준으로
    // 목록에서 센다.
    val changedCount = remember(list, overrides) {
        list?.count { SoundCatalog.typeOf(it, overrides) != it.defaultType } ?: 0
    }

    // 한참 내려간 채로 찾는 말이나 거르기를 바꾸면 결과의 중간부터 보인다. 결과가 찾기 칸 바로 밑부터 보이게 올린다.
    // **찾는 말이 있으면 맨 위에 있어도 올린다.** 찾기 칸을 누르면 자판이 화면 아래 절반을 가리는데, 맨 위의 제목·안내가
    // 남은 자리를 다 차지해 결과가 하나도 보이지 않았다(#283, 에뮬레이터 계측 테스트). 거르기만 바꿀 때는 맨 위면
    // 그대로 둔다. 처음 값은 건너뛴다. 다른 탭에 갔다 돌아오면 이 화면이 새로 만들어지는데, 그때마다 올리면 내려 두었던
    // 자리를 잃는다.
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { search.text.toString() to filter }.drop(1).collect { (text, _) ->
            if (text.isNotEmpty() || listState.firstVisibleItemIndex > STICKY_HEADER_INDEX) {
                listState.scrollToItem(STICKY_HEADER_INDEX)
            }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.classify_reset_title),
            message = stringResource(R.string.classify_reset_message),
            confirmLabel = stringResource(R.string.classify_reset_confirm),
            cancelLabel = stringResource(R.string.classify_reset_cancel),
            onConfirm = {
                SettingsManager.resetSoundTypes()
                confirmReset = false
            },
            onDismiss = { confirmReset = false }
        )
    }

    // 목록을 다 읽기 전에는 그리지 않는다. 머리 몇 줄만으로 먼저 재면, 프로세스가 죽었다 살아날 때 되살린 스크롤
    // 위치가 그 짧은 목록에 맞춰 잘려 사라진다. 목록은 보통 한 번에 읽혀 있고(cachedOrNull), 처음에도 금방이다.
    if (list == null) return

    if (confirmBasic) {
        ConfirmDialog(
            title = stringResource(R.string.classify_basic_warning_title),
            message = stringResource(R.string.classify_basic_warning_message),
            confirmLabel = stringResource(R.string.classify_basic_warning_confirm),
            cancelLabel = stringResource(R.string.classify_basic_warning_cancel),
            // 모드만 바꾼다. 하나씩 바꿔 둔 소리는 기본 모드에서 그 묶음의 종류를 고를 때까지 그대로다.
            onConfirm = {
                SettingsManager.setClassifyAdvanced(false)
                confirmBasic = false
            },
            onDismiss = { confirmBasic = false }
        )
    }

    // 묻는 동안 목록이 바뀌어 카드를 찾지 못하면(있을 수 없는 일이지만) 창을 띄우지 않는다.
    val picking = pendingPick?.let { (id, type) ->
        sections.firstNotNullOfOrNull { section -> section.cards.firstOrNull { it.id == id } }?.let { it to type }
    }
    if (picking != null) {
        val (card, type) = picking
        ConfirmDialog(
            title = stringResource(R.string.classify_group_overwrite_title),
            message = stringResource(
                R.string.classify_group_overwrite_message,
                cardTitle(card, labelOf),
                SoundGroups.overwriteCount(card, overrides, type),
                stringResource(typeNameRes(type))
            ),
            confirmLabel = stringResource(R.string.classify_group_overwrite_confirm),
            cancelLabel = stringResource(R.string.classify_group_overwrite_cancel),
            onConfirm = {
                SettingsManager.setSoundTypes(card.members.map { it.name }, type)
                pendingPick = null
            },
            onDismiss = { pendingPick = null }
        )
    }

    LazyColumn(state = listState, modifier = Modifier.padding(horizontal = 24.dp).fillMaxSize()) {
        // 0번: 제목·설명·고급 모드 스위치. 1번(STICKY_HEADER_INDEX): 붙어 있는 찾기·거르기.
        item(key = "intro", contentType = "intro") {
            Text(
                stringResource(R.string.classify_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryTextColor,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(stringResource(R.string.classify_desc_groups), fontSize = 14.sp, lineHeight = 21.sp, color = SecondaryTextColor)
            // 한 소리가 여러 이름으로 함께 들린다는 작은 안내. 13sp 는 앱에서 이미 쓰는 작은 글씨다. 이 회색은 배경 위에서
            // 4.6:1 이라 더 작게 하지 않는다(ColorContrastTest).
            Text(
                stringResource(R.string.classify_sibling_note),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = SecondaryTextColor,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )
            // 켜는 것은 묻지 않는다. 끌 때만, 기본 모드에서 묶음을 고르면 하나씩 바꾼 것이 덮어써질 묶음이 있는지 본다.
            // 찾는 말·거르기와 상관없이 모든 묶음을, 지난번에 바꿔 둔 것까지 저장값으로 본다.
            ModernSwitch(
                label = stringResource(R.string.classify_advanced),
                desc = stringResource(R.string.classify_advanced_desc),
                checked = advanced
            ) { on ->
                when {
                    on -> SettingsManager.setClassifyAdvanced(true)
                    SoundGroups.anyMixed(sections, overrides) -> confirmBasic = true
                    else -> SettingsManager.setClassifyAdvanced(false)
                }
            }
        }

        // 찾기와 거르기는 긴 목록을 내려가도 손 닿는 곳에 있어야 해서 위에 붙여 둔다.
        // 배경을 칠해 밑으로 지나가는 줄이 비치지 않게 한다. 붙는 부분이 두꺼우면 목록이 보일 자리가 줄어서
        // 바꾼 개수와 되돌리기는 붙이지 않고 목록과 함께 올라가게 둔다.
        stickyHeader(key = "search", contentType = "search") {
            Column(modifier = Modifier.fillMaxWidth().background(BgColor).padding(bottom = 8.dp)) {
                SearchField(search)
                FilterRow(filter, typeColors) { filter = it }
            }
        }

        // "바꾼 소리" 는 묶음이 아니라 소리를 센다. 되돌리기도 소리마다 바꾼 것만 지운다.
        item(key = "changed", contentType = "changed") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Text(
                    stringResource(R.string.classify_changed_count, changedCount),
                    fontSize = 14.sp,
                    color = SecondaryTextColor,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { confirmReset = true }, enabled = changedCount > 0) {
                    Text(
                        stringResource(R.string.classify_reset_all),
                        color = if (changedCount > 0) AccentColor else SecondaryTextColor.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (shown.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Text(
                    stringResource(R.string.classify_empty),
                    fontSize = 15.sp,
                    color = SecondaryTextColor,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                )
            }
        }

        if (advanced) {
            items(advancedItems, key = { it.key }, contentType = { advancedContentType(it) }) { item ->
                when (item) {
                    is AdvancedItem.SectionHeading -> SectionHeading(item.type, typeColors)
                    is AdvancedItem.GroupHeading -> GroupHeading(cardTitle(item.card, labelOf), item.card.members.size)
                    is AdvancedItem.Row -> SoundTypeRow(
                        label = labelOf(item.entry),
                        entry = item.entry,
                        type = SoundCatalog.typeOf(item.entry, overrides),
                        typeColors = typeColors,
                        onPick = { SettingsManager.setSoundType(item.entry.name, it) }
                    )
                }
            }
        } else {
            for (section in shown) {
                item(key = "s:${section.type}", contentType = "section") { SectionHeading(section.type, typeColors) }
                items(section.cards, key = { "c:${it.card.id}" }, contentType = { "card" }) { shownCard ->
                    val card = shownCard.card
                    val state = SoundGroups.state(card, overrides)
                    GroupCard(
                        title = cardTitle(card, labelOf),
                        card = card,
                        state = state,
                        preview = SoundGroups.preview(card, shownCard.shown),
                        labelOf = labelOf,
                        typeColors = typeColors,
                        onPick = { type ->
                            // 소리마다 종류가 다른 카드는 먼저 묻는다. 하나씩 바꿔 둔 소리까지 덮어쓰기 때문이다.
                            // 모두 같은 카드는 묻지 않고 바로 바꾼다. 기본 종류를 고르면 바꾼 것이 모두 지워진다.
                            if (state is GroupState.Mixed) {
                                pendingPick = card.id to type
                            } else {
                                SettingsManager.setSoundTypes(card.members.map { it.name }, type)
                            }
                        }
                    )
                }
            }
        }

        item(key = "end", contentType = "end") { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

/** 고급 모드 목록의 칸마다 다른 모양. 같은 모양끼리만 화면 밖으로 나간 칸을 다시 쓰게 한다. */
private fun advancedContentType(item: AdvancedItem): String = when (item) {
    is AdvancedItem.SectionHeading -> "section"
    is AdvancedItem.GroupHeading -> "group"
    is AdvancedItem.Row -> "row"
}

/**
 * 새로 지은 묶음 이름의 찾기 값. 소리 이름처럼 앱 언어의 이름과 영어 이름 어느 쪽으로도 찾게 한다.
 * 영어 이름은 기본 문구(values)에 있어서, 앱 언어와 상관없이 꺼내려고 영어로 맞춘 리소스를 따로 만든다.
 * 이름이 소리 이름인 묶음은 그 소리의 찾기 값을 쓰므로([SoundGroups.filter]) 여기 넣지 않는다.
 */
private fun groupTitleKeys(context: Context, resources: Resources): Map<SoundGroup, String> {
    val english = context.createConfigurationContext(
        Configuration(resources.configuration).apply { setLocale(Locale.ENGLISH) }
    ).resources
    return SoundGroup.entries
        .filter { it.titleRes != 0 }
        .associateWith { SoundCatalog.searchKey(resources.getString(it.titleRes), english.getString(it.titleRes)) }
}

/** 카드(묶음)의 이름. 새로 지은 이름이 없는 묶음은 이름이 곧 묶음 이름인 소리의 보이는 이름이다. */
@Composable
@ReadOnlyComposable
private fun cardTitle(card: SoundCard, labelOf: (SoundEntry) -> String): String =
    card.titleEntry?.let(labelOf) ?: stringResource(card.titleRes)

@Composable
private fun SearchField(state: TextFieldState) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        state = state,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        placeholder = { Text(stringResource(R.string.classify_search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (state.text.isEmpty()) null else {
            {
                IconButton(onClick = { state.clearText() }) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_classify_clear_search))
                }
            }
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // 찾기를 누르면 자판만 내린다. 목록은 글자를 칠 때마다 이미 걸러져 있다.
        onKeyboardAction = { keyboard?.hide() },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = PrimaryTextColor,
            unfocusedTextColor = PrimaryTextColor,
            focusedContainerColor = CardColor,
            unfocusedContainerColor = CardColor,
            focusedBorderColor = AccentColor,
            unfocusedBorderColor = ChipColor,
            cursorColor = AccentColor,
            focusedPlaceholderColor = SecondaryTextColor,
            unfocusedPlaceholderColor = SecondaryTextColor,
            focusedLeadingIconColor = SecondaryTextColor,
            unfocusedLeadingIconColor = SecondaryTextColor,
            focusedTrailingIconColor = SecondaryTextColor,
            unfocusedTrailingIconColor = SecondaryTextColor
        )
    )
}

/**
 * 전체·종류 셋·바꾼 소리 중 하나를 고르는 줄. 한 줄에 다 들어가지 않으면 다음 줄로 내린다.
 * 옆으로 밀게 두었더니 흔한 폭(411dp)에서도 마지막 "바꾼 소리" 칸이 화면 밖에 숨어 있었다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(filter: SoundFilter, typeColors: Map<String, Color>, onFilter: (SoundFilter) -> Unit) {
    // 고른 칸은 색으로만 보이므로 selectableGroup 과 selectable 로 "선택됨" 을 읽어 준다(설정 탭의 모드 칸과 같은 방식).
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().selectableGroup().padding(bottom = 4.dp)
    ) {
        SoundFilter.entries.forEach { option ->
            val selected = option == filter
            val type = when (option) {
                SoundFilter.AMBIENT -> AiClassification.AMBIENT
                SoundFilter.SPEECH -> AiClassification.SPEECH
                SoundFilter.DANGER -> AiClassification.DANGER
                else -> null
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) AccentFillColor else ChipColor)
                    .selectable(selected = selected, role = Role.RadioButton) { onFilter(option) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                if (type != null) {
                    TypeDot(typeColors.getValue(type))
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    stringResource(
                        when (option) {
                            SoundFilter.ALL -> R.string.classify_filter_all
                            SoundFilter.CHANGED -> R.string.classify_filter_changed
                            else -> typeNameRes(type)
                        }
                    ),
                    color = if (selected) Color.White else PrimaryTextColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * 구역 머리: 그 아래 묶음들의 기본 종류. 묶음을 바꿔도 구역은 기본 종류라서 바꾼 묶음이 다른 구역으로 옮겨 가지 않는다.
 * 화면 읽어주기에서 구역 단위로 건너뛸 수 있게 제목으로 표시한다.
 */
@Composable
private fun SectionHeading(type: String, typeColors: Map<String, Color>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) { heading() }
    ) {
        TypeDot(typeColors.getValue(type))
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(typeNameRes(type)), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PrimaryTextColor)
    }
}

/**
 * 고급 모드의 묶음 머리: 묶음 이름과 소리 수. 누르는 칸이 아니라 제목이고, 바꾸는 것은 아래의 소리 줄이 맡는다.
 * 화면 읽어주기는 "사이렌 묶음, 소리 5개" 처럼 읽고, 제목이라 묶음 단위로 건너뛸 수 있다.
 */
@Composable
private fun GroupHeading(title: String, count: Int) {
    val description = stringResource(R.string.cd_classify_group_header, title, count)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp)
            .clearAndSetSemantics {
                heading()
                contentDescription = description
            }
    ) {
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = PrimaryTextColor,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(count.toString(), fontSize = 13.sp, color = SecondaryTextColor)
    }
}

/**
 * 기본 모드의 묶음 카드. 카드 어디를 눌러도 종류를 고르는 목록이 열린다.
 *
 * 화면 읽어주기에는 이름·지금 종류·(바꿨으면) 기본 종류·소리 수·미리보기를 한 번에 읽어 주고 드롭다운 목록이라고 알린다.
 * 소리 하나뿐인 묶음(전기톱 등)은 미리보기가 없고, 소리 한 줄과 같은 글로 읽는다.
 *
 * @param state 소리마다 종류가 다르면([GroupState.Mixed]) 종류 버튼에 점 없이 "일부 다름" 을 보이고, 목록에서도 고른
 *   것이 없다.
 */
@Composable
private fun GroupCard(
    title: String,
    card: SoundCard,
    state: GroupState,
    preview: GroupPreview,
    labelOf: (SoundEntry) -> String,
    typeColors: Map<String, Color>,
    onPick: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val current = (state as? GroupState.Uniform)?.type
    val typeName = stringResource(if (current == null) R.string.classify_type_mixed else typeNameRes(current))
    val defaultName = stringResource(typeNameRes(card.defaultType))
    val names = preview.names.map(labelOf)
    val count = card.members.size
    val spoken = names.joinToString(SPOKEN_SEPARATOR)
    val description = when {
        names.isEmpty() && current == card.defaultType -> stringResource(R.string.cd_classify_sound, title, typeName)
        names.isEmpty() -> stringResource(R.string.cd_classify_sound_changed, title, typeName, defaultName)
        current == card.defaultType -> stringResource(R.string.cd_classify_group, title, typeName, count, spoken)
        else -> stringResource(R.string.cd_classify_group_changed, title, typeName, defaultName, count, spoken)
    }
    // 이름 밑의 밝은 글씨. 묶음째 바꿨으면 지금 종류를 기본 종류와 함께 눈에 띄게 적고, 일부만 바꿨으면 그렇다고 적는다.
    // 되돌리려면 목록에서 "(기본)" 을 고른다.
    val accent = when {
        current == null -> stringResource(R.string.classify_group_mixed_note)
        current != card.defaultType -> stringResource(R.string.classify_group_changed_line, typeName, defaultName)
        else -> null
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.dropdownRow(description) { expanded = true }
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = PrimaryTextColor)
            if (accent != null) {
                Text(accent, fontSize = 13.sp, color = AccentColor, modifier = Modifier.padding(top = 2.dp))
            }
            if (names.isNotEmpty()) {
                PreviewLine(names, preview.more)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        TypeChooser(
            label = typeName,
            current = current,
            defaultType = card.defaultType,
            expanded = expanded,
            typeColors = typeColors,
            onDismiss = { expanded = false },
            onPick = {
                expanded = false
                onPick(it)
            }
        )
    }
}

/**
 * 카드 이름 밑의 미리보기: 묶음의 소리 몇 개와 나머지 수("+N"). 두 줄까지 보이고 넘치면 줄임표로 자른다.
 *
 * "+N" 은 글에 섞지 않고 따로 재서 마지막 줄 끝에 붙인다. 글에 섞으면 이름이 길 때 줄임표와 함께 잘려 보이지 않고,
 * 글 옆 칸에 두면 두 줄로 꺾인 글의 마지막 줄이 짧을 때 "+N" 이 줄 끝에서 멀리 떨어져 보였다.
 */
@Composable
private fun PreviewLine(names: List<String>, more: Int) {
    val style = LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SecondaryTextColor)
    // 마지막 줄이 어디서 끝나는지는 글을 잰 결과로만 안다. 글을 잴 때 받아 두고 같은 재기 안에서 읽는다.
    val textLayout = remember { arrayOfNulls<TextLayoutResult>(1) }
    Layout(
        modifier = Modifier.padding(top = 2.dp),
        content = {
            Text(
                names.joinToString(" • "),
                style = style,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { textLayout[0] = it }
            )
            if (more > 0) Text(stringResource(R.string.classify_group_more, more), style = style, maxLines = 1)
        }
    ) { measurables, constraints ->
        val gap = 4.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val morePlaceable = measurables.getOrNull(1)?.measure(loose)
        // "+N" 이 들어갈 자리를 비워 두고 글을 잰다. 그래야 마지막 줄 끝에 늘 붙일 수 있다.
        val reserved = if (morePlaceable == null) 0 else morePlaceable.width + gap
        val text = measurables[0].measure(loose.copy(maxWidth = (loose.maxWidth - reserved).coerceAtLeast(0)))
        val result = textLayout[0]
        // 줄 끝은 글의 시작 쪽에서 잰 거리로 구한다. 오른쪽에서 왼쪽으로 쓰는 언어(아랍어)는 줄이 왼쪽에서 끝난다.
        val lastLine = (result?.lineCount ?: 1) - 1
        val lineEnd = when {
            result == null -> text.width
            result.getParagraphDirection(result.getLineStart(lastLine)) == ResolvedTextDirection.Rtl ->
                text.width - result.getLineLeft(lastLine).roundToInt()
            else -> result.getLineRight(lastLine).roundToInt()
        }
        val moreX = lineEnd + gap
        val moreY = if (morePlaceable == null || result == null) 0 else {
            result.getLineBaseline(lastLine).roundToInt() - morePlaceable[FirstBaseline]
        }
        val width = maxOf(text.width, if (morePlaceable == null) 0 else moreX + morePlaceable.width)
        layout(width, text.height) {
            text.placeRelative(0, 0)
            morePlaceable?.placeRelative(moreX, moreY)
        }
    }
}

/**
 * 고급 모드의 소리 한 줄. 줄 어디를 눌러도 종류를 고르는 목록이 열린다.
 *
 * 화면 읽어주기에는 이름·지금 종류·(바꿨으면) 기본 종류를 한 번에 읽어 주고 드롭다운 목록이라고 알린다.
 * 글자마다 따로 멈추면 521줄을 지나기가 너무 길다.
 */
@Composable
private fun SoundTypeRow(
    label: String,
    entry: SoundEntry,
    type: String,
    typeColors: Map<String, Color>,
    onPick: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val changed = type != entry.defaultType
    val typeName = stringResource(typeNameRes(type))
    val defaultName = stringResource(typeNameRes(entry.defaultType))
    val description = if (changed) {
        stringResource(R.string.cd_classify_sound_changed, label, typeName, defaultName)
    } else {
        stringResource(R.string.cd_classify_sound, label, typeName)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.dropdownRow(description) { expanded = true }
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp, color = PrimaryTextColor)
            if (changed) {
                // 바꾼 소리는 기본 종류를 밝은 글자로 함께 보여 준다. 되돌리려면 목록에서 "(기본)" 을 고른다.
                Text(
                    stringResource(R.string.classify_changed_from, defaultName),
                    fontSize = 13.sp,
                    color = AccentColor,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        TypeChooser(
            label = typeName,
            current = type,
            defaultType = entry.defaultType,
            expanded = expanded,
            typeColors = typeColors,
            onDismiss = { expanded = false },
            onPick = {
                expanded = false
                onPick(it)
            }
        )
    }
}

/**
 * 누르면 종류 목록이 열리는 줄(소리 한 줄, 묶음 카드)의 바탕. 화면 읽어주기에는 [description] 한 덩어리를 드롭다운
 * 목록으로 알리고, 안의 글자는 따로 읽지 않는다.
 */
private fun Modifier.dropdownRow(description: String, onOpen: () -> Unit): Modifier = this
    // 아래 여백은 누르는 자리 밖에 둔다. 안에 두면 다음 줄과의 빈 칸까지 눌린다.
    .padding(bottom = 8.dp)
    .fillMaxWidth()
    .clip(RoundedCornerShape(16.dp))
    .background(CardColor)
    .clickable(role = Role.DropdownList, onClick = onOpen)
    .clearAndSetSemantics {
        contentDescription = description
        role = Role.DropdownList
        onClick { onOpen(); true }
    }
    .heightIn(min = MIN_TOUCH_HEIGHT)
    .padding(horizontal = 16.dp, vertical = 12.dp)

/**
 * 줄 오른쪽의 종류 버튼과 그 밑에 열리는 종류 목록. 소리 한 줄과 묶음 카드가 함께 쓰고, 여는 것은 줄 전체가 맡는다.
 *
 * @param label 버튼에 보이는 글자. 보통은 지금 종류의 이름이다.
 * @param current 지금 종류. null 이면 소리마다 종류가 다른 묶음이라 점을 그리지 않고, 목록에서도 고른 것이 없다.
 * @param defaultType 목록에서 "(기본)" 을 붙일 종류.
 */
@Composable
private fun TypeChooser(
    label: String,
    current: String?,
    defaultType: String,
    expanded: Boolean,
    typeColors: Map<String, Color>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    // 목록은 이 버튼 밑에 열린다.
    Box {
        // 종류 이름이 긴 언어(독일어 "Umgebungsgeräusche")에서 버튼이 줄의 절반을 넘게 먹어 소리 이름이
        // 좁아졌다. 폭에 상한을 두고, 넘치면 하이픈을 넣어 두 줄로 꺾는다.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = TYPE_BUTTON_MAX_WIDTH)
                .clip(RoundedCornerShape(10.dp))
                .background(ChipColor)
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
        ) {
            if (current != null) {
                TypeDot(typeColors.getValue(current))
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = PrimaryTextColor,
                style = wrappingLabelStyle(),
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = SecondaryTextColor)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            containerColor = ChipColor
        ) {
            SOUND_TYPES.forEach { option ->
                val name = stringResource(typeNameRes(option))
                val isCurrent = option == current
                DropdownMenuItem(
                    text = {
                        Text(
                            if (option == defaultType) stringResource(R.string.classify_type_default, name) else name,
                            color = PrimaryTextColor,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    leadingIcon = { TypeDot(typeColors.getValue(option)) },
                    // 지금 종류는 체크 표시와 함께 화면 읽어주기에 "선택됨" 으로도 알린다.
                    trailingIcon = if (isCurrent) {
                        { Icon(Icons.Default.Check, contentDescription = null, tint = AccentColor) }
                    } else {
                        null
                    },
                    onClick = { onPick(option) },
                    modifier = Modifier.semantics { selected = isCurrent }
                )
            }
        }
    }
}

/** 종류의 색. 설정 탭에서 고른 색을 그대로 쓴다. 흰색·검은색 같은 색도 보이게 테두리를 두른다. */
@Composable
private fun TypeDot(color: Color) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
    )
}

/**
 * 확인·취소 창. 모두 되돌리기, 묶음 전체 바꾸기, 기본 모드로 돌아가기가 함께 쓰고, 색 고르기·권한 안내 창과 같은 모양이다.
 * 창은 본문에 남는 높이만 주므로, 큰 글꼴에서도 끝까지 읽게 본문을 스크롤되게 둔다.
 */
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardColor,
        title = { Text(title, color = PrimaryTextColor, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(message, color = SecondaryTextColor, fontSize = 15.sp, lineHeight = 24.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = AccentColor, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel, color = SecondaryTextColor) }
        }
    )
}

private fun typeNameRes(type: String?): Int = when (type) {
    AiClassification.DANGER -> R.string.sound_type_danger
    AiClassification.SPEECH -> R.string.sound_type_speech
    else -> R.string.sound_type_ambient
}
