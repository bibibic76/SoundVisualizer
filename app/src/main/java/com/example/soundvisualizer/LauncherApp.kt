package com.example.soundvisualizer

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.soundvisualizer.help.HelpTab
import kotlinx.coroutines.launch

@Composable
fun LauncherApp(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAddTile: () -> Unit
) {
    // 탭 이름은 화면 맨 위에 있다. 큰 화면에서 한 손으로 쓰면 거기까지 손이 가지 않으므로
    // 화면 아무 데서나 좌우로 밀어도 넘어가게 한다.
    val pagerState = rememberPagerState(initialPage = selectedTab) { TAB_COUNT }
    val scope = rememberCoroutineScope()

    // 액티비티가 탭을 정해 주는 경로(타일 길게 누르기, 멈춤 안내의 홈 이동)를 그대로 살린다.
    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab) pagerState.animateScrollToPage(selectedTab)
    }
    // 밀어서 넘긴 결과를 액티비티에 돌려준다. 화면 회전과 복귀 때 보던 탭이 유지되는 것은
    // 액티비티가 들고 있는 값이 맡는다.
    //
    // **멈춘 뒤의 탭**만 돌려준다. 지나가는 탭까지 돌려주면, 위 효과가 2번에서 0번으로 밀어 주는 동안
    // 1번을 지나는 순간 액티비티 값이 1 이 되고, 그 값으로 효과가 다시 만들어지면서 돌던 애니메이션이
    // 취소된다. 꺼짐 안내로 홈까지 가야 하는데 가운데 설정 탭에 멈춘다(#185).
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { onSelectTab(it) }
    }

    // 액티비티가 화면을 시스템 바 밑까지 그리므로, 탭과 내용은 상태 표시줄·내비게이션 바·카메라 구멍을 비켜 놓는다.
    // 비켜 놓은 자리에도 앱 배경색이 보이는 것은 바깥 Surface 가 창 전체를 칠하기 때문이다.
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        // TabRow. 번역된 탭 이름이 길어 한 줄에 다 안 들어가면 옆으로 밀어 볼 수 있게 한다.
        // selectableGroup 은 화면 읽어주기에 "셋 중 몇 번째"를 알려준다.
        //
        // 선택 표시는 액티비티가 든 값이 아니라 지금 보고 있는 쪽(currentPage)을 따른다. 밀다가 절반을
        // 넘기는 순간 밑줄이 따라오므로, 손을 떼기 전에도 어디로 가는지 보인다.
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().padding(24.dp)) {
            TabButton(stringResource(R.string.tab_home), pagerState.currentPage == 0) {
                scope.launch { pagerState.animateScrollToPage(0) }
            }
            Spacer(modifier = Modifier.width(24.dp))
            TabButton(stringResource(R.string.tab_settings), pagerState.currentPage == 1) {
                scope.launch { pagerState.animateScrollToPage(1) }
            }
            Spacer(modifier = Modifier.width(24.dp))
            TabButton(stringResource(R.string.tab_help), pagerState.currentPage == 2) {
                scope.launch { pagerState.animateScrollToPage(2) }
            }
        }

        // 남은 높이를 전부 준다. 세 탭 모두 fillMaxSize 라 한 쪽씩 화면을 채운다.
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> HomeTab(onStart, onStop, onAddTile)
                1 -> SettingsTab()
                else -> HelpTab()
            }
        }
    }
}

/** 홈·설정·도움말. [LauncherApp] 의 탭 수와 [MainActivity] 의 TAB_* 이 같은 수를 가리킨다. */
private const val TAB_COUNT = 3

@Composable
fun TabButton(title: String, isSelected: Boolean, onClick: () -> Unit) {
    Column(
        // 선택 여부가 색과 밑줄로만 보이면 화면 읽어주기 사용자는 어느 탭을 보고 있는지 알 수 없다.
        // selectable(Role.Tab) 이 "선택됨"과 탭이라는 것을 함께 읽어준다.
        modifier = Modifier.selectable(
            selected = isSelected,
            // 기본 리플이 어두운 배경에서 검은 사각형처럼 번쩍인다. 탭에는 밑줄로 충분하다.
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Tab,
            onClick = onClick
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            color = if (isSelected) PrimaryTextColor else SecondaryTextColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        // 밑줄은 선택 여부와 상관없이 항상 자리를 차지한다. 빼버리면 열 높이가 3dp 줄어
        // 탭을 옮길 때마다 글자가 위아래로 튄다.
        Box(
            modifier = Modifier
                .height(3.dp)
                .width(40.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(if (isSelected) PrimaryTextColor else Color.Transparent)
        )
    }
}
