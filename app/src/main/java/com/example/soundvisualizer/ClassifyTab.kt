package com.example.soundvisualizer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext

/** 줄 배경 위에 올리는 칸(종류 버튼, 거르기 칸)의 색. 설정 탭의 고르지 않은 모드 칸과 같다. */
private val ChipColor = Color(0xFF333A44)

/** 분류 탭에서 고를 수 있는 종류. 드롭다운과 거르기 칸이 이 순서로 보인다. */
private val SOUND_TYPES = listOf(AiClassification.AMBIENT, AiClassification.SPEECH, AiClassification.DANGER)

/** 목록에서 붙어 있는 찾기·거르기 줄의 자리. 그 앞은 제목과 설명 하나뿐이다. */
private const val STICKY_HEADER_INDEX = 1

/** 줄 오른쪽 종류 버튼의 최대 폭. 흔한 폭(411dp)에서 줄 안쪽의 절반쯤이다. */
private val TYPE_BUTTON_MAX_WIDTH = 150.dp

/**
 * 분류 탭. AI 가 알아듣는 소리를 모두 늘어놓고, 소리마다 지금 어느 종류로 구분되는지 보여 주고 바꾸게 한다.
 *
 * 바꾼 종류는 [SettingsManager.setSoundType] 이 저장하고, AI 는 [SettingsManager.soundTypeOverride] 로 읽어 간다.
 * **AI 판정에 연결하는 일은 #291 이 맡는다.** 그 전까지 이 탭은 개발자 모드에서만 보이고([LauncherApp]),
 * 맨 위에 아직 쓰이지 않는다는 안내를 둔다.
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

    // 찾는 말은 입력 칸이 직접 들고 있게 한다. 값을 받아 다시 넘겨주는 방식(value/onValueChange)은 목록이 다시 그려지는
    // 동안 입력이 몰리면 글자가 빠졌다(에뮬레이터에서 "bell" 이 "bll" 로 들어감).
    val search = rememberTextFieldState()
    val query = search.text.toString()
    var filter by rememberSaveable { mutableStateOf(SoundFilter.ALL) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    val typeColors = mapOf(
        AiClassification.AMBIENT to Color(SettingsManager.colorAmbient.collectAsState().value),
        AiClassification.SPEECH to Color(SettingsManager.colorSpeech.collectAsState().value),
        AiClassification.DANGER to Color(SettingsManager.colorDanger.collectAsState().value)
    )

    val list = entries
    val labelOf = { entry: SoundEntry -> labels.getOrElse(entry.index) { entry.name } }
    val searchKeys = remember(list, labels) { list?.map { SoundCatalog.searchKey(labelOf(it), it.name) }.orEmpty() }
    val visible = remember(list, searchKeys, overrides, query, filter) {
        if (list == null) emptyList() else SoundCatalog.filter(list, searchKeys, overrides, query, filter)
    }
    // 저장값에는 지금 목록에 없는 이름이 남아 있을 수 있다(모델의 이름이 바뀐 뒤 등). "바꾼 소리" 거르기와 같은 기준으로
    // 목록에서 센다.
    val changedCount = remember(list, overrides) {
        list?.count { SoundCatalog.typeOf(it, overrides) != it.defaultType } ?: 0
    }

    // 한참 내려간 채로 찾는 말이나 거르기를 바꾸면 결과의 중간부터 보인다. 결과가 찾기 칸 바로 밑부터 보이게 올린다.
    // 맨 위(제목이 보이는 자리)에 있을 때는 그대로 둔다. 처음 값은 건너뛴다. 다른 탭에 갔다 돌아오면 이 화면이
    // 새로 만들어지는데, 그때마다 올리면 내려 두었던 자리를 잃는다.
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { search.text.toString() to filter }.drop(1).collect {
            if (listState.firstVisibleItemIndex > STICKY_HEADER_INDEX) listState.scrollToItem(STICKY_HEADER_INDEX)
        }
    }

    if (confirmReset) {
        ResetDialog(
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

    LazyColumn(state = listState, modifier = Modifier.padding(horizontal = 24.dp).fillMaxSize()) {
        // 0번: 제목과 설명. 1번(STICKY_HEADER_INDEX): 붙어 있는 찾기·거르기.
        item {
            Text(
                stringResource(R.string.classify_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryTextColor,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            // AI 판정에 연결되기 전(#291)이라 바꿔도 색과 진동이 그대로다. 바꿨는데 왜 안 되느냐고 헷갈리지 않게 먼저 알린다.
            Text(
                stringResource(R.string.classify_preview_note),
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = AccentColor,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(stringResource(R.string.classify_desc), fontSize = 14.sp, lineHeight = 21.sp, color = SecondaryTextColor)
            Text(
                stringResource(R.string.classify_danger_note),
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = SecondaryTextColor,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )
        }

        // 찾기와 거르기는 521줄을 내려가도 손 닿는 곳에 있어야 해서 위에 붙여 둔다.
        // 배경을 칠해 밑으로 지나가는 줄이 비치지 않게 한다. 붙는 부분이 두꺼우면 목록이 보일 자리가 줄어서
        // 바꾼 개수와 되돌리기는 붙이지 않고 목록과 함께 올라가게 둔다.
        stickyHeader {
            Column(modifier = Modifier.fillMaxWidth().background(BgColor).padding(bottom = 8.dp)) {
                SearchField(search)
                FilterRow(filter, typeColors) { filter = it }
            }
        }

        item {
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

        if (list != null && visible.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.classify_empty),
                    fontSize = 15.sp,
                    color = SecondaryTextColor,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                )
            }
        }

        items(visible, key = { it.index }) { entry ->
            SoundTypeRow(
                label = labelOf(entry),
                entry = entry,
                type = SoundCatalog.typeOf(entry, overrides),
                typeColors = typeColors,
                onPick = { SettingsManager.setSoundType(entry.name, it) }
            )
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

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
                    .background(if (selected) AccentColor else ChipColor)
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
 * 소리 한 줄. 줄 어디를 눌러도 종류를 고르는 목록이 열린다.
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
        modifier = Modifier
            // 아래 여백은 누르는 자리 밖에 둔다. 안에 두면 다음 줄과의 빈 칸까지 눌린다.
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardColor)
            .clickable(role = Role.DropdownList) { expanded = true }
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.DropdownList
                onClick { expanded = true; true }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp)
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
                TypeDot(typeColors.getValue(type))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    typeName,
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
                onDismissRequest = { expanded = false },
                containerColor = ChipColor
            ) {
                SOUND_TYPES.forEach { option ->
                    val name = stringResource(typeNameRes(option))
                    val current = option == type
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (option == entry.defaultType) stringResource(R.string.classify_type_default, name) else name,
                                color = PrimaryTextColor,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        leadingIcon = { TypeDot(typeColors.getValue(option)) },
                        // 지금 종류는 체크 표시와 함께 화면 읽어주기에 "선택됨" 으로도 알린다.
                        trailingIcon = if (current) {
                            { Icon(Icons.Default.Check, contentDescription = null, tint = AccentColor) }
                        } else {
                            null
                        },
                        onClick = {
                            expanded = false
                            onPick(option)
                        },
                        modifier = Modifier.semantics { selected = current }
                    )
                }
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

@Composable
private fun ResetDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    // 색 고르기·권한 안내 창과 같은 모양이다.
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardColor,
        title = { Text(stringResource(R.string.classify_reset_title), color = PrimaryTextColor, fontWeight = FontWeight.Bold) },
        text = {
            Text(stringResource(R.string.classify_reset_message), color = SecondaryTextColor, fontSize = 15.sp, lineHeight = 24.sp)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.classify_reset_confirm), color = AccentColor, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.classify_reset_cancel), color = SecondaryTextColor) }
        }
    )
}

private fun typeNameRes(type: String?): Int = when (type) {
    AiClassification.DANGER -> R.string.sound_type_danger
    AiClassification.SPEECH -> R.string.sound_type_speech
    else -> R.string.sound_type_ambient
}
