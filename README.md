# hompage-comments

[yule.studio](https://github.com/yule-studio/hompage) 의 **코멘트 월** 백엔드.

방문자가 남긴 코멘트를 **이 저장소의 GitHub Issue** 로 기록한다. 별도의 DB도, 관리자 페이지도 없다 — 이슈 목록이 곧 데이터베이스이고, GitHub UI 가 곧 관리 도구다.

```
방문자 ──▶ hompage (Vite/React)  ──▶  comment-api (Spring Boot)  ──▶  GitHub Issues
   코멘트 작성            fetch                토큰 보관             yule-studio/hompage-comments
```

토큰은 서버에만 존재한다. 브라우저에는 절대 내려가지 않는다.

---

## 왜 이렇게 만들었나

| | |
|---|---|
| **DB 없음** | 개인 사이트의 코멘트 몇십 건에 DB 를 띄우고 백업할 이유가 없다. |
| **알림 공짜** | 이슈가 열리면 GitHub 가 알아서 메일/모바일 알림을 보낸다. |
| **관리 공짜** | 스팸은 이슈를 닫으면 사라지고, 좋은 코멘트는 `pinned` 라벨로 맨 위에 고정된다. |
| **이력 보존** | 수정·삭제가 전부 이슈 히스토리에 남는다. |

---

## 화면

### 코멘트 월 (hompage `/#contact`)

![코멘트 월](docs/comment-wall.png)

왼쪽은 메일 폼, 오른쪽이 이 서비스가 채우는 코멘트 월이다.
이름 · 코멘트 · 이미지 첨부를 받아 `Post Comment` 로 등록한다.

### 코멘트 목록

![코멘트 목록](docs/comment-list.png)

`pinned` 라벨이 붙은 코멘트는 초록 배경으로 맨 위에 고정된다. 하트를 누르면 좋아요가 오른다.

> 위 화면의 코멘트는 문서용 **샘플 데이터**다.

---

## 이슈로 어떻게 저장되나

| 코멘트 | GitHub Issue |
|---|---|
| 이름 | 이슈 제목 |
| 본문 | 이슈 본문 |
| 첨부 이미지 | `assets/comments/{uuid}.jpg` 로 커밋 후 본문에 마크다운 삽입 |
| 좋아요 | 본문 끝의 `<!-- meta:likes=N -->` (렌더링되지 않음) |
| 고정 | `pinned` 라벨 |
| 숨김 | 이슈를 닫으면 목록에서 사라짐 |

GitHub API 에는 이슈 첨부파일 업로드가 없다 (웹 UI 의 드래그&드롭은 비공개 엔드포인트다). 그래서 이미지는 저장소에 **커밋**해서 raw URL 을 얻는 방식을 쓴다.

좋아요를 리액션으로 두지 않은 이유도 같은 맥락이다 — 모든 방문자가 서비스의 토큰 하나를 공유하므로 GitHub 가 리액션을 한 개로 합쳐버린다. 그래서 카운터를 본문에 적고 갱신한다.

---

## API

베이스 URL 은 배포 위치. 아래는 로컬 기준.

### `GET /api/comments`

열려 있는 이슈를 최신순으로 반환한다 (`pinned` 우선, 최대 100건).

```json
[
  {
    "id": 12,
    "name": "지훈",
    "comment": "홈랩 구성 글 잘 봤습니다!",
    "imageUrl": null,
    "likes": 4,
    "pinned": true,
    "createdAt": "2026-08-12T02:10:00Z",
    "issueUrl": "https://github.com/yule-studio/hompage-comments/issues/12"
  }
]
```

### `POST /api/comments`

```json
{
  "name": "지훈",
  "comment": "홈랩 구성 글 잘 봤습니다!",
  "image": "data:image/jpeg;base64,..."
}
```

`name` 40자, `comment` 2000자 제한. `image` 는 선택이며 브라우저에서 720px / JPEG 0.7 로 줄여서 보낸다. → `201 Created` + 생성된 코멘트.

### `POST /api/comments/{id}/like`

좋아요 +1. → 갱신된 코멘트.

### 응답 코드

| 코드 | 뜻 |
|---|---|
| `429` | 레이트 리밋 (IP 당 10분에 5회) |
| `503` | `GITHUB_TOKEN` 미설정 |

---

## 설정

### 1. 토큰 발급

[Fine-grained personal access token](https://github.com/settings/tokens?type=beta) 을 **이 저장소에만** 스코프해서 만든다.

| 권한 | 용도 |
|---|---|
| Issues: **Read and write** | 코멘트 등록 · 조회 · 좋아요 |
| Contents: **Read and write** | 이미지 첨부 (안 쓰면 생략 가능) |

### 2. 환경변수

```bash
cp .env.example .env
# .env 를 열어 GITHUB_TOKEN 을 채운다 — .env 는 gitignore 되어 있다
```

| 변수 | 기본값 | 설명 |
|---|---|---|
| `GITHUB_TOKEN` | — | **필수.** 없으면 API 가 503 을 반환한다 |
| `GITHUB_OWNER` | `yule-studio` | 이슈가 열릴 소유자 |
| `GITHUB_REPO` | `hompage-comments` | 이슈가 열릴 저장소 |
| `GITHUB_BRANCH` | `main` | 이미지가 커밋될 브랜치 |
| `GITHUB_ATTACHMENTS` | `true` | 이미지 첨부 사용 여부 |
| `ALLOWED_ORIGINS` | `http://localhost:5273,http://localhost:5173` | CORS 허용 오리진 (쉼표 구분) |
| `PORT` | `8080` | 서비스 포트 |

### 3. 실행

```bash
set -a && source .env && set +a
./gradlew :apps:comment-api:bootRun
```

빌드한 jar 로 돌릴 때:

```bash
./gradlew :apps:comment-api:build
java -jar apps/comment-api/build/libs/comment-api-0.1.0.jar
```

### 4. 프론트 연결

`hompage` 의 `.env` 에 이 서비스 주소를 적는다. 비워두면 `http://localhost:8080` 을 쓴다.

```
VITE_COMMENTS_API=https://comments.yule.studio
```

---

## 저장소 구조

모노레포다. 서비스가 늘어나면 `apps/` 아래에 폴더를 하나 더 만들고 `settings.gradle.kts` 에 한 줄 추가하면 된다.

```
hompage-comments/
├─ apps/
│  └─ comment-api/              Spring Boot 3.4 · Java 21
│     └─ src/main/java/studio/yule/comments/
│        ├─ CommentApiApplication.java
│        ├─ CommentService.java      코멘트 ↔ 이슈 변환
│        ├─ github/                  GitHub REST 클라이언트
│        └─ web/                     컨트롤러 · DTO · 레이트 리밋 · CORS
├─ docs/                        README 스크린샷
├─ settings.gradle.kts          모듈 목록
└─ build.gradle.kts             공통 빌드 설정
```

---

## 운영 메모

- **모더레이션** — 이슈를 닫으면 월에서 사라진다. `pinned` 라벨을 붙이면 맨 위로 고정된다.
- **레이트 리밋** — 프로세스 메모리 기반이라 인스턴스 하나를 전제로 한다. 여러 대로 늘릴 거면 공유 저장소가 필요하다.
- **리버스 프록시** — `X-Forwarded-For` 를 보고 클라이언트를 식별하므로, 프록시 뒤에 둘 때 이 헤더를 전달해야 한다.
- **공개 범위** — 코멘트는 이 저장소의 이슈이므로 **누구나 볼 수 있다**. 비공개로 두려면 저장소를 private 으로 바꾸면 되고, 그때는 목록 조회도 토큰을 통해 나간다.
