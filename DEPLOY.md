# Deploy 24/7 com GitHub Actions + Neon

Roda o monitor duas vezes por dia sem manter servidor nenhum ligado. Custo: £0.

**Nenhum passo aqui exige colar segredo em lugar público.** Os valores vão direto do seu navegador
para os GitHub Secrets, que são cifrados e nunca aparecem nos logs.

---

## Por que precisa de um banco externo

O runner do GitHub é destruído ao fim de cada execução. Se o banco morrer junto:

- toda execução veria todo itinerário **pela primeira vez**;
- `firstSighting` seria sempre `true`;
- **nenhuma queda de preço seria jamais detectada.**

O histórico é o coração do sistema, então ele precisa viver fora do runner. O Neon tem free tier de
sobra para isto — o banco inteiro deste projeto ocupa poucos megabytes por ano.

---

## Passo 1 — Criar o banco no Neon

Entre em <https://neon.tech> e crie a conta (login com GitHub serve, e não pede cartão).

Em **Create project**, use exatamente estas configurações:

| Campo | Valor | Por quê |
|---|---|---|
| **Plan** | `Free` | 0,5 GB de storage e 100 CU-hours/mês. Este projeto usa ~1,5 CU-hora e poucos MB por ano |
| **Project name** | `flight-monitor` | qualquer nome serve |
| **Postgres version** | `16`, `17` ou `18` | todas funcionam — veja a nota abaixo |
| **Region** | a mais próxima (ex.: `AWS South America (São Paulo)`) | latência é irrelevante com duas conexões por dia; escolha por preferência |
| **Database name** | `neondb` (padrão) | o nome não importa, só precisa bater com a URL |

Tudo o mais fica no padrão. Especificamente **não** precisa mexer em:

- **Autosuspend / Scale to zero** — o padrão (suspender após 5 min ocioso) é ideal aqui. O banco
  dorme entre as execuções e acorda em segundos na primeira conexão, o que não atrapalha nada e é
  o que mantém o consumo de CU-hours perto de zero.
- **Autoscaling** — o mínimo do free tier já sobra.
- **Branches** — o `main`/`production` padrão basta. Branching é útil para desenvolvimento, não aqui.

### Sobre a versão do Postgres

O Flyway 10.10 (que o Spring Boot 3.3.5 traz) declara suporte oficial só até o PG 16, e emite este
aviso em versões mais novas:

```
WARN  Flyway upgrade recommended: PostgreSQL 18.6 is newer than this version of Flyway
      and support has not been tested. The latest supported version of PostgreSQL is 16.
```

**É um aviso, não uma falha.** Verificado rodando o pipeline inteiro contra PostgreSQL 18.6:

| Verificação | Resultado |
|---|---|
| Migrações V1 e V2 | ✅ aplicadas |
| Ofertas gravadas | ✅ 2 linhas em `flight_offer` |
| Filtro de país bloqueado | ✅ rota via DOH rejeitada, `BLOCKED_COUNTRY` |
| `price_history`, `offer_source`, `alert` | ✅ gravados |
| Data local vs. instante | ✅ `2027-01-22 18:05+00` com `2027-01-23T03:05` preservado |

Ou seja: **pode usar 16, 17 ou 18.** O 16 é o único que não emite o aviso e é o que o
`docker-compose.yml` usa localmente; o 18 funciona igual e custa uma linha de WARN no log.

O risco residual de usar 17/18 é uma migração *futura* que use sintaxe recente e que o Flyway
interprete de forma não testada. As migrações atuais são DDL trivial (`CREATE TABLE`,
`ALTER TABLE ADD COLUMN`, índices, chaves estrangeiras), então esse risco hoje é próximo de zero.
Se quiser eliminá-lo, a saída é subir o Flyway — o que exige subir o Spring Boot junto, porque o
Flyway 11 tem incompatibilidade conhecida com o Boot 3.3.

### A connection string

Depois de criar, o Neon mostra algo como:

```
postgresql://SEU_USUARIO:SUA_SENHA@ep-algo-1234.sa-east-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require
```

Copie e guarde — você vai quebrá-la em três partes no passo 3.

- **Escolha a conexão "Direct", não a "Pooled".** O Neon oferece as duas; a pooled tem `-pooler` no
  hostname. O pooler existe para muitas conexões simultâneas, e este job abre no máximo 5, duas
  vezes por dia. A própria documentação do Neon recomenda a direta para drivers nativos como o
  pgJDBC.
- **`sslmode=require` fica.** O Neon exige TLS e o pgJDBC entende esse parâmetro.
- **`channel_binding=require` pode ficar ou sair.** Testei: o pgJDBC aceita a URL sem reclamar, mas
  simplesmente **ignora** esse parâmetro (ele é do libpq, não do driver Java). Não quebra nada e
  também não protege nada. Deixar é mais simples do que editar.

---

## Passo 2 — Subir o repositório

Na pasta do projeto:

```bash
git init
git add .
git commit -m "Flight monitor"
```

Confirme que o `.env` **não** entrou (ele está no `.gitignore`):

```bash
git ls-files | grep -c "^\.env$"
```

Precisa imprimir `0`. Se imprimir `1`, pare e me avise antes de dar push.

Crie o repositório no GitHub (pode ser **privado** — os 2.000 minutos grátis cobrem folgado os
~180 min/mês que este workflow usa) e faça o push:

```bash
git remote add origin git@github.com:SEU_USUARIO/flight-monitor.git
git branch -M main
git push -u origin main
```

---

## Passo 3 — Cadastrar os secrets

No GitHub: **Settings → Secrets and variables → Actions → New repository secret**.

| Secret | Valor | Onde achar |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://ep-algo-1234.sa-east-1.aws.neon.tech/neondb?sslmode=require` | a string do Neon, **trocando** `postgresql://` por `jdbc:postgresql://` e **removendo** `usuario:senha@` |
| `DATABASE_USER` | a parte antes de `:` no trecho de credenciais | idem |
| `DATABASE_PASSWORD` | a parte entre `:` e `@` | idem |

Traduzindo com um exemplo. O Neon te dá:

```
postgresql://neondb_owner:npg_Ab3xY9@ep-cool-dawn-12345678.sa-east-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require
                └──── user ────┘ └ senha ┘ └───────────────── host ─────────────────┘ └ db ┘
```

Vira:

```
DATABASE_URL       jdbc:postgresql://ep-cool-dawn-12345678.sa-east-1.aws.neon.tech/neondb?sslmode=require
DATABASE_USER      neondb_owner
DATABASE_PASSWORD  npg_Ab3xY9
```

Note que o `:5432` não aparece — o Neon usa a porta padrão e o pgJDBC assume 5432 sozinho. Se
quiser deixar explícito, `.../neondb` vira `...:5432/neondb`, tanto faz.
| `TELEGRAM_BOT_TOKEN` | seu token | o mesmo do `.env` |
| `TELEGRAM_CHAT_ID` | `-100...` | o mesmo do `.env` |
| `SERPAPI_KEY` | sua chave | o mesmo do `.env` |
| `TRAVELPAYOUTS_TOKEN` | seu token | o mesmo do `.env` |

> **O prefixo `jdbc:` é obrigatório** e é o erro mais comum aqui. A string que o Neon mostra é para
> clientes Postgres nativos; o driver Java precisa de `jdbc:postgresql://...`. E o usuário e a senha
> vão em campos separados, não embutidos na URL.

Os valores do seu `.env` local, sem revelar nada, para conferir tamanho:

```bash
sh scripts/check-env.sh
```

---

## Passo 4 — Rodar uma vez na mão

**Actions → Scheduled flight search → Run workflow.**

O que esperar no log:

```
Migrating schema "public" to version "1 - initial schema"
Migrating schema "public" to version "2 - local calendar fields"
One-shot mode: running a single search, then exiting
SEARCH_STARTED trip=tokyo-poa-2027 queries=12 providers=[travelpayouts, serpapi]
trip=tokyo-poa-2027 status=COMPLETED offers=8 accepted=7 rejected=1 alerts=0
```

As migrações só aparecem na primeira execução. `alerts=0` é o resultado correto enquanto nada
estiver dentro do orçamento.

Se falhar, o workflow manda o motivo no Telegram com o link do run — silêncio deste workflow seria
indistinguível de "não achou nada barato", que é justamente a confusão que o monitor existe para
evitar.

---

## Passo 5 — Deixar no automático

Nada a fazer: o cron `0 5,17 * * *` já está no workflow (duas vezes por dia, UTC).

Duas ressalvas honestas do agendador do GitHub:

- é *best-effort* e atrasa alguns minutos sob carga;
- **é desativado após 60 dias sem atividade no repositório.** Um commit qualquer reativa. Para uma
  viagem em janeiro/2027 isso pode acontecer, então vale conferir a aba Actions de vez em quando.

---

## Onde mudar a configuração depois do deploy

| O que mudar | Onde | Vale para |
|---|---|---|
| Datas, aeroportos, orçamento, países bloqueados, limiares | `src/main/resources/application.yml` (**commitado**) | Actions **e** local |
| Tokens e senhas | GitHub Secrets | Actions |
| Qualquer coisa, só para testar na sua máquina | `.env` | **só local** |

> Este é o ponto que mais confunde: **o `.env` não vai para o GitHub.** Se você mudar a data da
> viagem no `.env` e não no `application.yml`, o monitor na nuvem continua procurando a data antiga
> — sem erro nenhum, só o resultado errado. Mude no `application.yml` e commite.

---

## Migrando depois sem perder histórico

O banco local (Docker) e o do Neon são independentes. Se quiser levar o histórico já coletado:

```bash
docker compose exec -T db pg_dump -U flightmonitor flightmonitor > backup.sql
psql "postgresql://usuario:senha@host.neon.tech/neondb?sslmode=require" < backup.sql
```

Não é obrigatório: sem isso o Neon começa do zero e as primeiras observações voltam a ser
"primeira observação", sem quedas detectadas até a segunda leitura.

Quando o Actions estiver rodando, pode desligar o local:

```bash
docker compose down
```

O volume com o histórico local permanece; `docker compose down -v` é que apaga.
