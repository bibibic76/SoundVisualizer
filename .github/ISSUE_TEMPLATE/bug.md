---
name: 🐛 버그 수정 (bugfix / hotfix)
about: 버그 제보 및 수정 — 개발 중 버그는 bugfix, 배포된 버전의 긴급 버그는 hotfix
title: ""
labels: bug
assignees: ""
---

## 발견 위치
<!-- 하나만 체크하세요. 체크한 위치에 따라 브랜치가 달라집니다. -->
- [ ] 개발 중인 코드(`develop`) — 아직 배포 전 → `bugfix`
- [ ] 배포된 버전(`main`) — 긴급 → `hotfix`

## 버그 설명
<!-- 어떤 문제가 발생하는지 적어주세요. -->

## 재현 방법
1. 
2. 

## 기대 동작
<!-- 원래 어떻게 동작해야 하는지 적어주세요. -->

## 환경
- 기기 / Android 버전:
- 앱 버전:

## 완료 조건
- [ ] 

---
> 브랜치
> - 개발 중 버그: `develop`에서 `bugfix/{이슈번호}-{내용}` 으로 생성 (예: `bugfix/27-overlay-crash`)
> - 배포된 버전의 긴급 버그: `main`에서 `hotfix/{이슈번호}-{내용}` 으로 생성 (예: `hotfix/34-search-bug`)
