# crf-sql-edit

ClipReport v5 `.crf` 리포트 템플릿 안의 **SQL을 디자이너 없이 커맨드라인에서 조회·추출·변경**하는 작은 도구.

ClipReport 엔진의 공개 API(`com.clipsoft.clipreport.base.Rexpert4`)로 `.crf`를 객체 모델로 읽어, 개별
데이터셋의 SQL을 덤프하거나 교체한 뒤 정상 `.crf`로 다시 쓴다. 엔진을 거치므로 REX 바이너리 포맷과
필드 단위 SQL 암호화가 자동 처리된다 — 역공학이 필요 없다.

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

# 3) .sql 파일을 편집한 뒤, 변경을 담은 새 .crf 작성
.\crf-sql.ps1 -Lib $lib set "report.crf" "report.new.crf" "DS1=.\sql_out\DS1.sql"
```

`set`은 여러 데이터셋을 동시에 받고, Pre/Post SQL은 `이름.pre=<파일>` / `이름.post=<파일>`로 지정한다:

```powershell
.\crf-sql.ps1 -Lib $lib set "r.crf" "r.new.crf" `
  "DS1=.\sql_out\DS1.sql" "DS2=.\sql_out\DS2.sql" "DS1.pre=.\sql_out\DS1.pre.sql"
```

`set`은 작성 후 결과 파일을 다시 읽어 각 변경이 반영됐는지 검증하고 `[VERIFY PASS]`를 출력한다(실패 시 0이 아닌 종료코드).

### 래퍼 없이 실행

```
java -Dfile.encoding=UTF-8 -cp "dist/crf-sql-edit.jar;<lib>/ClipReport5.0.<build>.jar;<lib>/ClipReport5.0-Common.jar" CrfSqlEdit list report.crf
```

(macOS/Linux는 클래스패스 구분자로 `;` 대신 `:` 사용.)

## 소스에서 빌드 (선택)

```powershell
.\build.ps1 -Lib $lib      # src\CrfSqlEdit.java -> dist\crf-sql-edit.jar 컴파일
```

## 동작과 유의점

- **입력 `.crf`는 제자리에서 변경하지 않는다** — 출력은 항상 지정한 파일로 간다.
- SQL 기반 데이터셋만 수정한다. 스토어드 프로시저/CSV/XML/JSON/SAP 데이터셋은 그대로 둔다.
- 출력 파일은 엔진이 재직렬화한다. 실무상 의미:
  - 현재 엔진 포맷(**REX31**)으로 저장되며 **네이티브 디자이너에서 정상적으로 열린다**(검증됨);
  - 데이터셋 SQL이 출력에 **평문**으로 저장된다(엔진은 어느 쪽이든 읽음) — `.crf`가 SQL을 암호화 상태로
    유지해야 하는 요건이 있다면 이 도구는 맞지 않는다;
  - 디자인타임 미리보기 썸네일은 디자이너가 다음에 열 때 재생성된다;
  - 연결별 드라이버-jar 힌트(디자이너 전용 필드)는 다시 기록되지 않는다 — 서버 엔진은 JDBC 드라이버를
    자기 클래스패스에서 해석한다.
- 운영에 반영하기 전, 변경한 리포트를 한 번 디자이너로 열거나 렌더해 확인할 것.

## 라이선스

MIT — [LICENSE](LICENSE) 참고.
