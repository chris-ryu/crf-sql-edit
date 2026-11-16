# crf-sql-edit

ClipReport v5 `.crf` 리포트 템플릿 안의 **SQL을 디자이너 없이 커맨드라인에서 조회·추출·변경**하는 작은 도구.

ClipReport 엔진의 공개 API(`com.clipsoft.clipreport.base.Rexpert4`)로 `.crf`를 읽어 데이터셋 SQL을 덤프하고,
**해당 데이터셋의 SQL만 바꾼 새 `.crf`**를 만든다. REX 바이너리 포맷과 SQL 암호화는 엔진이 처리한다 —
역공학이 필요 없다.

> CLIPSOFT와 무관하며 ClipReport 소프트웨어를 포함하지 않는다. **ClipReport v5 엔진 JAR은 본인이 보유한
> 라이선스 설치본에서 직접 제공**해야 한다(아래 요구사항 참고).

## 요구사항

- **Java 7+** 런타임(실행용). 소스 재빌드 시에만 JDK 필요.
- **ClipReport v5 엔진 JAR** (본인 설치본에서 제공), 예:
  - `ClipReport5.0.<build>.jar`
  - `ClipReport5.0-Common.jar`

  한 폴더에 모아 두고 `-Lib` 인자로 지정한다. 이 JAR들은 상용이라 저장소에 **포함하지 않는다**.

JDK 없이 바로 실행할 수 있도록, 이 도구의 자체 컴파일 결과물 `dist/crf-sql-edit.jar`(Java 7 타깃)를 포함한다.

## 사용법 (Windows PowerShell)

```powershell
# -Lib는 ClipReport*.jar 들이 있는 폴더를 가리킨다
$lib = "C:\path\to\clipreport\lib"

# 1) 데이터셋과 현재 SQL 조회
.\crf-sql.ps1 -Lib $lib list "report.crf"

# 2) 모든 데이터셋 SQL을 편집 가능한 .sql 파일로 추출
.\crf-sql.ps1 -Lib $lib dump "report.crf" ".\sql_out"

# 3) .sql 파일을 편집한 뒤, 그 SQL만 바꾼 새 .crf 작성
.\crf-sql.ps1 -Lib $lib patch "report.crf" "report.new.crf" "DS1=.\sql_out\DS1.sql"
```

`patch`는 여러 데이터셋을 한 번에 받는다:

```powershell
.\crf-sql.ps1 -Lib $lib patch "r.crf" "r.new.crf" "DS1=.\sql_out\DS1.sql" "DS2=.\sql_out\DS2.sql"
```

### 래퍼 없이 실행

```
java -Dfile.encoding=UTF-8 -cp "dist/crf-sql-edit.jar;<lib>/ClipReport5.0.<build>.jar;<lib>/ClipReport5.0-Common.jar" CrfSqlEdit list report.crf
```

(macOS/Linux는 클래스패스 구분자로 `;` 대신 `:` 사용.)

## 명령

| 명령 | 하는 일 |
|---|---|
| `list <in.crf>` | 데이터셋 목록, 타입, SQL 길이, 첫 줄 미리보기 |
| `dump <in.crf> <outDir>` | 각 SQL 데이터셋을 `<outDir>/<이름>.sql`로 저장(UTF-8). Pre/Post SQL은 `<이름>.pre.sql` / `<이름>.post.sql` |
| `patch <in.crf> <out.crf> <이름>=<sqlFile> [...]` | 지정한 데이터셋의 쿼리 SQL을 바꾸고 **그 밖에는 아무것도 바꾸지 않는다** |
| `diff <a.crf> <b.crf>` | 두 리포트를 엔진으로 읽어 값이 다른 곳을 모두 나열. 같으면 종료코드 0, 다르면 1 |
| `set <in.crf> <out.crf> <이름>=<sqlFile> [...]` | 엔진이 리포트 전체를 다시 쓰는 방식. Pre/Post SQL(`<이름>.pre=`, `<이름>.post=`)도 받는다. 아래 "왜 `patch`인가" 참고 |

## `patch`가 하는 일

출력 파일은 **입력 파일에서 해당 데이터셋의 저장된 SQL 레코드만 교체한 것**이다. 새 레코드(암호화된 SQL)는
엔진이 만든 것을 그대로 쓰고, 나머지 바이트는 입력과 동일하다. 포맷 버전도 그대로다.

쓰기 전에 결과를 엔진으로 다시 읽어 두 가지를 확인한다. 둘 다 맞아야 파일을 쓰고 `[VERIFY PASS]`를 출력한다
(아니면 아무것도 쓰지 않고 0이 아닌 종료코드).

- 각 데이터셋의 SQL이 요청한 대로다(바꾸지 않은 데이터셋은 그대로).
- 리포트 전체 모델이 입력과 **요청한 쿼리 문자열에서만** 다르다.

알아 둘 동작:

- **SQL 길이가 4의 배수(문자 수)이면 끝에 공백 1자를 붙인다**(`NOTE`로 알림). 텍스트가 암호 블록을 딱 채우는
  경우 디자이너와 엔진이 레코드를 다르게 저장하는데, 한 글자를 더해 그 경우를 피하면 디자이너가 저장한 것과
  같은 레코드가 된다.
- SQL 파일 맨 앞의 UTF-8 BOM은 무시한다.
- 저장된 SQL은 CRLF만 쓰는데 새 SQL에 CRLF가 아닌 줄바꿈이 있으면 `NOTE`로 알린다(바꾸지는 않는다).
- 쿼리 SQL만 다룬다. Pre/Post SQL, 쿼리가 비어 있던 데이터셋에 쿼리를 넣는 일은 `set`으로만 할 수 있다.
- 입력과 같은 경로로는 쓰지 않는다. SQL이 하나도 바뀌지 않으면 입력의 바이트 단위 사본을 쓴다.

## 왜 `patch`인가 (`set`의 한계)

`set`은 엔진의 writer로 리포트 전체를 다시 직렬화한다. 그런데 엔진의 writer는 읽은 것을 전부 되쓰지 않는다.
엔진 5.0.226으로 디자이너가 저장한 리포트들을 `set`한 뒤 `diff`로 비교하면, SQL 외에 리포트당 100~480개
값이 달라진다:

- 포맷이 REX30에서 REX31로 바뀐다;
- 표의 분할선이 "없음"에서 "실선 0.75"로, 대각선 두께가 0.5에서 0.75로 바뀐다;
- 체크/라디오 이미지가 사라진다;
- 점자 관련 설정, 비어 있는 참조 항목이 바뀐다.

원래 값을 setter로 되돌려 다시 써도 유지되지 않는다. 그래서 SQL만 바꾸려면 `patch`를 쓴다. `set`의 결과가
무엇을 바꿨는지는 `diff <in.crf> <out.crf>`로 볼 수 있다.

## 공통 유의점

- **입력 `.crf`는 제자리에서 변경하지 않는다** — 출력은 항상 지정한 파일로 간다.
- SQL 기반 데이터셋만 다룬다. 스토어드 프로시저/CSV/XML/JSON/SAP 데이터셋은 그대로 둔다.
- 운영에 반영하기 전, 변경한 리포트를 한 번 디자이너로 열거나 렌더해 확인할 것.

## 소스에서 빌드 (선택)

```powershell
.\build.ps1 -Lib $lib      # src\CrfSqlEdit.java -> dist\crf-sql-edit.jar 컴파일
```

## 테스트

실제 도구를 실제 리포트에 돌리는 종단 테스트다. 엔진 JAR과, SQL 데이터셋이 2개 이상인 `.crf`를 직접 지정한다.
리포트 옆에는 아무것도 쓰지 않고 임시 폴더에서만 작업한다.

```powershell
.\test\run-tests.ps1 -Lib $lib -Crf "C:\reports\a.crf","C:\reports\b.crf"
```

## 라이선스

MIT — [LICENSE](LICENSE) 참고.
