# Flight Monitor

Monitora automaticamente o preço de passagens aéreas, guarda o histórico, detecta quedas reais e
avisa no Telegram. **Não compra nada** — só observa, filtra, compara e notifica.

Configuração de fábrica: **Tokyo (HND/NRT) → Porto Alegre (POA)**, ida em 09/01/2027 com
flexibilidade de ±2 dias, volta chegando ao Japão **antes de 23/01/2027 23:59 JST**, orçamento
**£1.300–£1.500**, rejeitando qualquer itinerário que faça conexão em países da lista de bloqueio.
Tudo isso é configuração — nenhuma data, aeroporto ou país aparece no código.

### Viagens monitoradas

O `application.yml` tem três viagens, pesquisadas e guardadas separadamente. As duas primeiras
formam o plano de Tóquio (dois bilhetes); a terceira é uma viagem à parte.

| | Tóquio ⇄ Brasil (multi-city) | Rio → Porto Alegre | Porto Alegre → Rio |
|---|---|---|---|
| id | `tokyo-rio-poa-2027` | `rio-poa-2027-01-13` | `poa-rio-2027` |
| Trechos | 09/01 HND, NRT → GIG, SDU · 19/01 POA → HND, NRT | 13/01 GIG, SDU → POA, só ida | ida 11/01 POA → GIG, SDU · volta 13/01 |
| Alternativa | volta saindo de GRU, GIG ou SDU, pesquisada a cada 2 dias; só alerta se £200+ mais barata | — | — |
| Horário | livre | livre | ida até 11:59, preferindo 05:00; volta preferindo 11:00 |
| Orçamento | abaixo de £1.700 excelente; **acima de £2.000 descartado** (nem resolve a volta) | até **R$ 350**, abaixo de R$ 250 excelente | **R$ 650–800** |
| Chamadas SerpApi/dia | 2 (+2 a cada 2 dias) | 1 | 3 |

Tudo roda uma vez por dia no GitHub Actions, ~210 chamadas/mês, dentro da cota grátis de 250.
O preço do multi-city é o **total** do bilhete (os dois trechos). A Travelpayouts não cota multi-city
e pula essa viagem. Um bilhete único de três trechos (Tóquio → Rio → POA → Tóquio) foi cotado em
set/2026 a partir de £2.359, por isso o trecho Rio → POA é monitorado como bilhete separado.

Para acrescentar outra viagem, basta outro bloco em `flight-monitor.trips` com `id` único; os campos
de horário, `budget-currency`, `combine-origins`, `combine-destinations`, `max-options-per-search`,
`return-origin-airports` (open jaw), `alternative-return-origins` e `discard-above-budget` são opcionais;
sem `return-window-*` a viagem é só ida.

---

## Índice

1. [O que o projeto faz](#1-o-que-o-projeto-faz)
2. [Arquitetura](#2-arquitetura)
3. [Instalação](#3-instalação)
4. [Telegram: criar o bot](#4-telegram-criar-o-bot)
5. [Telegram: obter o chat id](#5-telegram-obter-o-chat-id)
6. [Providers: qual usar e por quê](#6-providers-qual-usar-e-por-quê)
7. [Configurar datas](#7-configurar-datas)
8. [Configurar países e aeroportos bloqueados](#8-configurar-países-e-aeroportos-bloqueados)
9. [Rodar localmente](#9-rodar-localmente)
10. [Rodar com Docker](#10-rodar-com-docker)
11. [Rodar 24/7 de graça](#11-rodar-247-de-graça)
12. [Trocar de provider](#12-trocar-de-provider)
13. [Limitações das fontes](#13-limitações-das-fontes)
14. [Custos](#14-custos)
15. [Como interpretar os alertas](#15-como-interpretar-os-alertas)
16. [API e dashboard](#16-api-e-dashboard)
17. [Testes](#17-testes)

---

## 1. O que o projeto faz

A cada execução (uma vez por dia no GitHub Actions) o sistema executa este pipeline, para cada viagem:

```
planejar consultas → perguntar aos providers → normalizar → filtrar → deduplicar
        → salvar → comparar com o histórico → decidir se é notícia → Telegram
```

**Filtros aplicados** (um itinerário precisa passar em todos):

| Regra | Comportamento |
|---|---|
| Rota | Rejeita se **qualquer** aeroporto do itinerário — origem, destino ou **qualquer conexão, nos dois sentidos** — estiver em país/aeroporto bloqueado |
| Ida | Precisa partir dentro da janela em torno da data alvo |
| Volta | Precisa **chegar** ao Japão antes do prazo. Não olha a data de partida do voo de volta |
| Duração | Rejeita acima do limite absoluto (48h por trecho); entre 30h/36h/42h só classifica |

**Bandas de preço:** abaixo de £1.300 = excelente · £1.300–£1.500 = dentro do orçamento ·
acima de £1.500 = acima do orçamento.

**Bandas de duração** (trecho mais longo): ≤30h excelente · ≤36h aceitável · ≤42h longo ·
>42h muito longo · >48h rejeitado.

**Ranking** — cada componente é normalizado para 0..1 e multiplicado pelo seu peso, então os pesos
são diretamente comparáveis e o total soma 100 com os padrões:

| Componente | Peso | O que mede |
|---|---:|---|
| Preço | 45 | 1.0 a 70% do piso do orçamento, 0.0 a 150% do teto |
| Duração | 25 | 1.0 em ≤20h no trecho mais longo, 0.0 no limite absoluto |
| Escalas | 15 | 1.0 sem escala, caindo até 0.0 no máximo configurado. Usa o **pior** dos dois trechos |
| Bagagem | 5 | 1.0 despachada incluída · 0.6 só de mão · 0.3 não informada · 0.0 nada incluído |
| Data | 10 | 1.0 na data alvo, caindo pela janela; ×0.8 se a volta cai na margem de segurança |

Rota bloqueada não recebe pontuação nenhuma: é rejeitada antes de chegar ao ranking.

Todos os pesos são configuráveis (`SCORE_*_WEIGHT`).

### Regras que o sistema nunca quebra

- **Nunca inventa dado.** Se a fonte não informou bagagem, aparece
  `Baggage information unavailable`. Se não existe link direto, `bookingUrl` fica `null` e a fonte
  é mostrada. Se um provider falha, o resultado é `Provider unavailable` registrado — nunca
  "nenhum voo encontrado".
- **Nunca converte moeda com taxa fixa.** As taxas vêm do Frankfurter (referência do BCE, sem
  chave). Cada oferta guarda preço original, moeda, preço em GBP, a taxa usada e o timestamp dela.
  Se a taxa não estiver disponível, a oferta é descartada em vez de convertida com valor velho.
- **Falha fechada na segurança.** Se um aeroporto não está no catálogo, o país dele não pode ser
  verificado → rejeita. Se a fonte não revelou as conexões, a regra não pode ser aplicada →
  rejeita. Ambos configuráveis, ambos com padrão "rejeitar".
- **O primeiro preço é apenas uma observação.** Nenhuma queda é declarada antes da segunda leitura.

---

## 2. Arquitetura

Hexagonal (ports & adapters). O domínio não conhece HTTP, banco nem Telegram, e por isso as regras
de negócio são testadas em milissegundos sem subir nada.

```
src/main/java/com/flightmonitor/
├── domain/
│   ├── model/       Itinerary, ItineraryLeg, FlightSegment, PriceQuote, BaggageAllowance…
│   ├── rule/        SafetyRule, DateWindowRule, DurationRule, OfferEvaluator
│   ├── scoring/     OfferScorer
│   └── port/        FlightSearchProvider, NotificationPort, AirportCatalog,
│                    ExchangeRateProvider, ProviderBudget          ← as interfaces
├── application/     SearchOrchestrator (o pipeline), QueryPlanner, OfferStore,
│                    AlertDecider, ProviderBudgetService, SearchScheduler, OneShotRunner
├── providers/       serpapi/ · travelpayouts/ · fixture/          ← adapters de entrada
├── notification/    AlertMessageFormatter + telegram/             ← adapter de saída
├── infrastructure/  persistence/ · fx/ · catalog/ · web/          ← adapters de saída
└── config/          TripConfig, SafetyConfig, AlertConfig, ScoringConfig, ProviderConfig
```

**Por que `FlightSearchProvider` é uma interface:** o mercado de APIs de voo é instável (veja
[limitações](#13-limitações-das-fontes)). Trocar de fonte precisa custar uma classe nova e um bloco
de configuração, não uma reescrita.

### Banco de dados

| Tabela | Papel |
|---|---|
| `flight_offer` | Um itinerário distinto, com chave única `(trip_id, fingerprint)` |
| `offer_source` | O que **cada provider** disse sobre aquele itinerário: preço, moeda, taxa, link |
| `price_history` | Append-only. Uma linha por provider por execução |
| `alert` | Todo alerta decidido, com a mensagem exata e se o Telegram aceitou |
| `search_run` / `provider_run` | Auditoria de cada execução e de cada provider |
| `provider_usage` | Contador mensal persistente que segura os free tiers |

**Deduplicação.** O `fingerprint` é um SHA-256 de origem, destino, datas, companhias, números de
voo, escalas e aeroportos de conexão — deliberadamente **sem** preço e **sem** provider. Se o
provider A cota £1.450 e o B cota £1.445 para os mesmos voos, existe **uma** linha em
`flight_offer` com **duas** em `offer_source`; o preço de vitrine é o melhor dos dois e as duas
cotações continuam visíveis.

Todo timestamp é gravado `WITH TIME ZONE`. A aplicação nunca escreve hora local ingênua.

---

## 3. Instalação

Requisitos: **Java 21+** e **Maven 3.9+** para rodar local; ou só **Docker** para rodar em container.

```bash
git clone <url-do-repo>
cd flight-monitor
cp .env.example .env
# preencha .env — no mínimo TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID e uma chave de provider
```

---

## 4. Telegram: criar o bot

1. No Telegram, fale com [@BotFather](https://t.me/BotFather).
2. Envie `/newbot`.
3. Escolha um nome de exibição (ex.: `Monitor de Passagens`).
4. Escolha um username terminando em `bot` (ex.: `felipe_voos_bot`).
5. O BotFather devolve um token no formato `123456789:AAE...`. Coloque em `TELEGRAM_BOT_TOKEN`.

O token dá controle total do bot. Ele fica no `.env`, que está no `.gitignore`. Nunca comite.

## 5. Telegram: obter o chat id

**Conversa privada (mais simples):**

1. Mande qualquer mensagem para o seu bot (ele precisa receber uma primeiro).
2. Abra `https://api.telegram.org/bot<SEU_TOKEN>/getUpdates` no navegador.
3. Procure `"chat":{"id":123456789`. Esse número é o `TELEGRAM_CHAT_ID`.

Alternativa: fale com [@userinfobot](https://t.me/userinfobot), que responde com o seu id.

**Grupo:** adicione o bot ao grupo, mande uma mensagem lá e repita o `getUpdates`. O id de grupo é
negativo (ex.: `-1001234567890`).

**Conferir a configuração sem subir nada:**

```bash
sh scripts/check-env.sh --telegram
```

O script inspeciona o `.env`, aponta o que falta e, com `--telegram`, manda uma mensagem de teste
de verdade pela Bot API. Ele nunca imprime valores de segredos — só o tamanho deles.

**Conferir com a aplicação já rodando** (isto testa a fiação inteira: `.env` → configuração →
serviço, e não apenas se o token é válido):

```bash
curl -X POST http://localhost:8080/api/test-notification
```

Se o Telegram não estiver configurado, o alerta ainda é **decidido e gravado** — a tabela `alert`
guarda a mensagem e o motivo da não entrega. Consulte em `GET /api/alerts`.

---

## 6. Providers: qual usar e por quê

O cenário mudou muito em 2026. Isto é o que existe hoje:

| Fonte | Situação em 2026 | Uso aqui |
|---|---|---|
| **Amadeus Self-Service** | Portal desativado em 17/07/2026; chaves antigas mortas. Só Enterprise, que exige credenciamento IATA/ARC | ❌ inviável |
| **Kiwi Tequila** | Self-serve fechado desde 2024, só por convite | ❌ inviável |
| **Skyscanner oficial** | Só parceria comercial | ❌ inviável |
| **Duffel** | Test mode usa uma companhia fictícia (preços irreais); live exige conta comercial verificada | ⚠️ possível como provider extra |
| **SerpApi (Google Flights)** | Self-serve, ~100–250 buscas/mês grátis, dados reais **com escalas nomeadas** | ✅ **primário** |
| **Travelpayouts (Aviasales)** | Self-serve, grátis, sem custo por chamada; cache de tarifas das últimas 48h | ✅ secundário |
| **Frankfurter (BCE)** | Grátis, sem chave, sem cadastro | ✅ câmbio |

### SerpApi — o provider principal

É a única fonte self-serve grátis de 2026 que devolve o **itinerário completo**, com cada aeroporto
de conexão nomeado. É isso que torna a regra de países bloqueados aplicável de verdade em vez de
uma boa intenção.

> **Uma busca ida-e-volta custa DUAS chamadas de API.** A primeira resposta lista os voos de ida com
> um `departure_token`; os voos de volta só existem numa segunda chamada com esse token. O sistema
> contabiliza `1 + max-options-per-search` chamadas por busca e o guarda de orçamento respeita isso.

O orçamento mensal é **ritmado**: as chamadas não são liberadas de uma vez, e sim proporcionalmente
ao dia do mês. Sem isso, um agendamento frequente gastaria a cota inteira na primeira semana e
ficaria cego no resto do mês.

Cadastro: <https://serpapi.com/users/sign_up> → `SERPAPI_KEY`. Para ver sua cota real sem gastar
busca nenhuma:

```bash
curl -s "https://serpapi.com/account?api_key=SUA_CHAVE"
```

O plano grátis foi medido em **250 buscas/mês**; o padrão de 230 em `SERPAPI_MAX_CALLS_PER_MONTH`
deixa margem para disparos manuais.

**Pré-filtro de rota.** Saindo de Tóquio para a América do Sul, a opção mais barata é quase sempre
companhia do Golfo. Como os aeroportos da ida já vêm na 1ª chamada, o provider descarta as opções
bloqueadas **antes** de gastar a 2ª chamada resolvendo os voos de volta delas. Sem isso, a busca
pagava preço cheio para descobrir que a rota mais barata é inaceitável e voltava vazia. Isso é só
economia: o `SafetyRule` continua avaliando o itinerário completo depois, e o pré-filtro nunca
amplia o que é aceito.

**Retentativas.** Só são retentados erros que podem mudar de resposta: 5xx e 429. Um 401, um 400 ou
um erro de construção de requisição falham igual na segunda vez e só gastariam cota — assim como
uma falha que o próprio provider reportou no corpo ("invalid API key", "no results for this query"),
que é a resposta dele, não um soluço.

### Travelpayouts — o provider de amplitude

Grátis e praticamente sem limite, então varre todas as combinações de data e aeroporto em toda
execução e mantém o histórico de preços vivo mesmo quando o SerpApi já gastou a cota do dia.

> **O que ele não entrega:** a API informa a **quantidade** de conexões, nunca **quais** aeroportos.
> Por isso toda oferta dele nasce marcada como `COUNTS_ONLY` e é **rejeitada por padrão** com
> `ROUTE_UNVERIFIABLE`. Ela continua alimentando o histórico como sinal de tendência.
>
> Para aceitá-las mesmo assim: `REJECT_UNVERIFIABLE_ROUTES=false`. Isso é uma decisão explícita de
> abrir mão da garantia de roteamento em troca de cobertura.

Cadastro: <https://www.travelpayouts.com/> → `TRAVELPAYOUTS_TOKEN`.

### Fixture — só para teste

`FIXTURE_ENABLED=true` reproduz um payload gravado e permite exercitar o pipeline inteiro sem
nenhuma chave. **São dados simulados, nunca preços reais.** Vem desligado, loga um aviso a cada
chamada, e todo alerta que produz sai com um banner `⚠️ DADOS SIMULADOS`.

---

## 7. Configurar datas

```env
TARGET_DEPARTURE_DATE=2027-01-09     # data ideal da ida
DEPARTURE_FLEX_DAYS=2                # aceita 07/01 a 11/01
RETURN_WINDOW_START=2027-01-16       # datas de partida da volta a pesquisar
RETURN_WINDOW_END=2027-01-21

LATEST_RETURN_ARRIVAL=2027-01-23T23:59:00+09:00
MINIMUM_RETURN_BUFFER_HOURS=12
```

`LATEST_RETURN_ARRIVAL` é o prazo **duro** e é comparado com a **chegada** do voo de volta, não com
a partida. Um voo que sai de Porto Alegre no dia 22 e pousa em Tóquio no dia 24 é rejeitado.

Como todo timestamp no sistema carrega offset explícito (`+09:00`), a comparação é entre instantes
e não depende de adivinhar fuso em lugar nenhum.

`MINIMUM_RETURN_BUFFER_HOURS=12` cria um prazo **preferencial** 12h antes do duro (22/01 11:59 JST).
Chegadas entre o preferencial e o duro são aceitas, marcadas com aviso e pontuadas 20% abaixo.

---

## 8. Configurar países e aeroportos bloqueados

```env
BLOCKED_COUNTRIES=QA,AE,SA,KW,BH,OM,JO,LB,SY,IQ,YE,EG,LY,SD,DZ,TN,MA
BLOCKED_AIRPORTS=
REJECT_UNVERIFIABLE_ROUTES=true
REJECT_UNKNOWN_AIRPORTS=true
```

> **Esta lista é uma preferência pessoal, não uma avaliação de risco.**
> O sistema não calcula, não infere e não mantém nenhuma noção própria de "país seguro". Ele aplica
> uma regra mecânica: se o país ou o aeroporto está na lista, o itinerário é rejeitado. Editar a
> lista é a única forma de mudar esse comportamento, e ela vive inteiramente em configuração —
> nenhum nome de país aparece em qualquer arquivo `.java`.

Códigos de país são ISO 3166-1 alpha-2; aeroportos são IATA. `BLOCKED_AIRPORTS` bloqueia um
aeroporto específico independentemente do país (ex.: `BLOCKED_AIRPORTS=DOH,DXB`).

**O que é verificado:** origem, destino, **cada aeroporto de conexão** e cada escala declarada pela
fonte, **nos dois sentidos**. `Tokyo → Doha → São Paulo → Porto Alegre` é rejeitado;
`Tokyo → Frankfurt → São Paulo → Porto Alegre` é aceito.

O mapa aeroporto→país vem de `src/main/resources/airports.csv`, que é local de propósito: a regra
de bloqueio não pode depender de como uma API remota resolveu chamar um lugar. Aeroporto ausente do
catálogo não tem país verificável e é rejeitado — basta adicionar a linha ao CSV para ampliar a
cobertura.

---

## 9. Rodar localmente

**Sem instalar nada além de Java** (banco H2 em arquivo, nada de Postgres):

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

O mesmo `.env` do Docker é lido aqui (`spring.config.import` em `application.yml`), então não é
preciso exportar variável nenhuma na mão. Variáveis de ambiente reais, quando existem, têm
precedência sobre o arquivo.

Dashboard em <http://localhost:8080>. O histórico fica em `./data/flightmonitor.mv.db` e sobrevive a
reinícios. Bom para experimentar; para deixar rodando de verdade, use Postgres.

Disparar uma busca sem esperar o scheduler:

```bash
curl -X POST http://localhost:8080/api/search
```

Experimentar o pipeline inteiro sem nenhuma chave de API (dados simulados):

```bash
SPRING_PROFILES_ACTIVE=local FIXTURE_ENABLED=true mvn spring-boot:run
```

---

## 10. Rodar com Docker

```bash
cp .env.example .env
# preencha DATABASE_PASSWORD, TELEGRAM_* e pelo menos uma chave de provider
docker compose up -d
```

Sobem dois containers: a aplicação e um Postgres 16. O histórico fica num volume nomeado
(`flight-monitor-data`) porque é a única coisa aqui que não pode ser regerada.

```bash
docker compose logs -f app          # acompanhar
curl localhost:8080/actuator/health # saúde
docker compose down                 # parar (o volume permanece)
```

O `.env` nunca entra na imagem — é lido em tempo de execução via `env_file`.

---

## 11. Rodar 24/7 de graça

> **`docker compose up -d` no seu computador não é 24/7.** Os containers rodam na sua máquina: se
> ela desliga, suspende, ou o Docker Desktop fecha, não há busca nem alerta. O
> `restart: unless-stopped` só faz os containers voltarem quando o Docker sobe de novo.
>
> O monitor lida bem com uso intermitente: no boot ele consulta a **última busca gravada no banco**
> e, se já passou mais de um intervalo, busca na hora em vez de reiniciar a contagem. Sem isso, uma
> máquina que nunca fica ligada por 12 horas seguidas nunca faria busca alguma — e falharia em
> silêncio. Ainda assim, o que ele não pode fazer é acordar sozinho: máquina desligada, nada roda.

Situação verificada em setembro de 2026:

| Opção | Free tier hoje | Serve? |
|---|---|---|
| **Oracle Cloud Always Free** | VM ARM permanente, sem cartão obrigatório na maioria das regiões | ✅ **melhor opção**: `docker compose up -d` e esquece |
| **Render Hobby** | Web service grátis, sem cartão, mas **dorme** por inatividade | ⚠️ serve, com ressalva |
| **Fly.io** | Sem free tier para novas contas desde 2026; só trial de 2h/7 dias | ❌ |
| **Railway** | Modelo de trial/uso, não é mais "always free" | ❌ |
| **GitHub Actions** | Cron grátis (ilimitado em repo público) | ✅ com ressalvas — veja abaixo |

**Recomendação: Oracle Cloud Always Free.** É uma VM de verdade, roda o `docker compose` como está,
e nada dorme.

**Sobre o Render:** o plano grátis derruba o serviço por inatividade. Como este sistema é movido por
um scheduler interno e não por requisições HTTP, um serviço adormecido **não executa buscas**. Se
usar Render, prefira a alternativa do GitHub Actions abaixo, ou aceite lacunas no histórico.

### GitHub Actions (`.github/workflows/scheduled-search.yml`)

> **Passo a passo completo em [DEPLOY.md](DEPLOY.md)** — criar o banco no Neon, subir o repo,
> cadastrar os secrets e validar a primeira execução.

O workflow já está pronto: roda `--flight-monitor.search.one-shot=true`, faz uma busca e sai.

**A ressalva que importa:** o runner é destruído depois de cada job. O `DATABASE_URL` **precisa**
apontar para um Postgres externo e durável (Neon, Supabase e Aiven têm free tier). Sem isso, toda
execução vê todo itinerário pela primeira vez e nenhuma queda é jamais detectada — o histórico é o
componente central deste sistema.

Outras ressalvas: o cron do GitHub é *best-effort* e atrasa alguns minutos sob carga; ele é
desativado automaticamente após 60 dias sem atividade no repositório; e minutos são finitos em repo
privado (~240 min/mês nesse ritmo, contra 2.000 do free tier).

Secrets necessários em *Settings → Secrets and variables → Actions*: `DATABASE_URL`,
`DATABASE_USER`, `DATABASE_PASSWORD`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, e as chaves de
provider que você tiver.

---

## 12. Trocar de provider

1. Crie a classe estendendo `AbstractHttpFlightProvider` (que já traz timeout, retry com backoff
   exponencial, rate limit e o guarda de orçamento):

```java
@Component
public class MinhaFonteProvider extends AbstractHttpFlightProvider {

    @Override public String code() { return "minhafonte"; }
    @Override public String displayName() { return "Minha Fonte"; }

    // Declare honestamente o que a fonte revela. Se ela não nomeia as conexões,
    // devolva COUNTS_ONLY — é isso que impede o filtro de segurança de ser burlado.
    @Override public RouteDetailLevel routeDetailLevel() {
        return RouteDetailLevel.FULL_ITINERARY;
    }

    @Override protected ProviderResult performSearch(SearchQuery query) { … }
}
```

2. Adicione o bloco em `application.yml` sob `flight-monitor.providers.minhafonte`.

Só isso. O orquestrador injeta `List<FlightSearchProvider>` e descobre o novo automaticamente.
Para desligar um provider existente: `SERPAPI_ENABLED=false`.

---

## 13. Limitações das fontes

**Comuns a todas:**

- Preço de cache/pesquisa não é preço garantido. Nenhuma fonte gratuita garante disponibilidade no
  momento da compra — sempre confirme no site da companhia ou da agência.
- Bagagem raramente vem detalhada em busca; costuma aparecer só na etapa de reserva. Quando não
  vem, o sistema diz `Baggage information unavailable` em vez de supor.

**SerpApi:** cota mensal pequena no plano grátis; ida-e-volta custa 2 chamadas; não devolve link
direto para uma tarifa específica, então `bookingUrl` fica `null` e o alerta oferece um link de
**busca** no Google Flights, rotulado como tal.

**Travelpayouts:** cache das últimas 48h, não busca ao vivo; **não informa aeroportos de conexão**
(daí o `ROUTE_UNVERIFIABLE`); a API de busca em tempo real exige 50.000 usuários ativos/mês.

> **Cobertura medida em 07/09/2026 para esta viagem:** o cache retorna **zero resultados** para
> `HND→POA`, `NRT→POA`, `FLN` e `CWB`. Ele só tem dados para o hub popular (`HND→GRU`, `TYO→SAO`).
> Isso é esperado: o cache guarda o que usuários do Aviasales realmente pesquisaram, e ninguém
> pesquisa Tóquio→Porto Alegre com 16 meses de antecedência.
>
> Consequência prática: **na configuração atual quem faz todo o trabalho é o SerpApi.** O
> Travelpayouts fica ligado porque não custa nada e pode passar a ter dados se a rota começar a ser
> pesquisada — mas não conte com ele para esta viagem.
>
> Detalhe técnico observado: o parâmetro `t` dentro do campo `link` parece codificar a cadeia de
> aeroportos (`...HNDPEKGRU...`). É um formato interno não documentado, e usá-lo para decidir
> segurança de rota seria construir a regra mais crítica do sistema sobre algo que pode mudar sem
> aviso. Não foi implementado.

**Nada de scraping.** O projeto não tenta contornar CAPTCHA, autenticação, rate limit ou mecanismo
anti-bot de fonte nenhuma. Onde não há API utilizável, não há provider.

---

## 14. Custos

```
CURRENT_COST     £0 / mês
                 SerpApi free tier (~100-250 buscas/mês) + Travelpayouts (grátis, por afiliação)
                 + Frankfurter (grátis, sem chave) + Telegram Bot API (grátis)
                 + Oracle Cloud Always Free OU GitHub Actions (grátis em repo público)

POSSIBLE_COST    SerpApi: US$25/mês por 1.000 buscas, se quiser mais frequência ou mais rotas
                 Postgres gerenciado: US$0-19/mês se o free tier do Neon/Supabase não bastar
                 VPS: US$4-6/mês se o Oracle Always Free deixar de existir

LIMITATIONS      Free tiers não são promessa contratual. A Amadeus desligou o Self-Service em
                 julho de 2026 e o Fly.io removeu o free tier — os dois estavam nesta lista antes.
                 É exatamente por isso que providers são plugáveis: se uma fonte fechar, escreve-se
                 uma classe e troca-se um bloco de configuração, sem tocar no resto.
```

O guarda de orçamento (`provider_usage`) impede que a aplicação atravesse silenciosamente um free
tier e comece a gerar cobrança. `GET /api/status` mostra quantas chamadas foram gastas no mês.

---

## 15. Como interpretar os alertas

| Cabeçalho | Quando dispara |
|---|---|
| 🚨 **EXCELENTE PREÇO** | O preço cruzou para baixo do piso do orçamento agora. Ignora o cooldown |
| 🔥 **NOVO MENOR PREÇO** | Menor valor já registrado para **este** itinerário. Ignora o cooldown |
| ✈️ **QUEDA DE PREÇO** | Caiu o suficiente para valer uma mensagem, respeitando o cooldown |
| 🆕 **NOVA OPÇÃO DENTRO DO ORÇAMENTO** | Itinerário visto pela primeira vez já dentro do orçamento |

**Anti-spam.** Uma queda só conta se passar de `MIN_PRICE_DROP_GBP` (£30) **ou** de
`MIN_PRICE_DROP_PERCENT` (3%). £1.450 → £1.449 não gera nada. Além disso há cooldown de 12h por
itinerário e um teto de 5 alertas por execução.

**Aeroportos alternativos.** `POA` é o destino pretendido; `FLN`, `CWB` e `GRU` estão na lista
apenas como alternativas. Pousar em outra cidade custa um voo de conexão, um ônibus ou uma noite
fora, então uma alternativa só vira alerta se estiver pelo menos
`ALTERNATIVE_DESTINATION_MIN_SAVING_GBP` (£150) abaixo do melhor preço conhecido em POA. Enquanto
não houver preço em POA para comparar, ela fica em silêncio — não há como chamar de pechincha algo
sem referência. Em ambos os casos a oferta continua gravada e visível no dashboard e na API.

Para que essas alternativas sejam de fato pesquisadas, o planejador **reserva uma consulta por
execução** para elas e faz rodízio entre `CWB`, `FLN` e `GRU` (dia do ano módulo três). Sem essa
reserva, a ordenação por prioridade gastaria todas as consultas em POA e as alternativas nunca
seriam consultadas.

Os dois tipos que furam o cooldown ainda precisam passar dos limites de queda, então nenhum deles
dispara por ruído.

**O que ler na mensagem:**

- `👀 Primeira observação` — ainda não há histórico; o valor é um preço observado, não uma queda.
- `⚠️ Alternative arrival airport: FLN` — não é Porto Alegre; foi aceito por estar na lista de
  destinos alternativos.
- `⚠️ Return lands …, inside the 12h safety margin` — chega antes do prazo duro mas dentro da
  margem de segurança.
- `🧳 Baggage information unavailable` — a fonte não informou. Não significa "sem bagagem".
- `🔗 Abrir busca no Google Flights` + *(link de busca, não de uma tarifa específica)* — a fonte não
  fornece link direto para aquela tarifa, e o sistema não inventa um.
- `⚠️ DADOS SIMULADOS` — o provider fixture está ligado. Não é preço real.

---

## 16. API e dashboard

Dashboard em `/` — melhor preço atual, menor histórico, última e próxima busca, gráfico de
histórico, tabela ordenável por preço/duração/score, e o estado dos providers com a lista de
bloqueio em vigor.

| Endpoint | O que faz |
|---|---|
| `GET /api/status` | Viagens, providers, orçamento consumido, próxima busca, lista de bloqueio |
| `GET /api/offers?sort=price\|score\|recent&acceptedOnly=false` | Ofertas |
| `GET /api/offers/best` | Melhores por score |
| `GET /api/offers/cheapest` | Mais baratas |
| `GET /api/offers/{id}/price-history` | Histórico de um itinerário |
| `GET /api/price-history` | Histórico da viagem (só ofertas aceitas) |
| `GET /api/alerts` | Alertas enviados, com a mensagem exata e o status de entrega |
| `GET /api/rejections` | Contagem de rejeições por motivo |
| `POST /api/search` | Executa uma busca agora (409 se já houver uma em andamento) |
| `POST /api/test-notification` | Manda uma mensagem de teste pelo canal real (412 se não configurado) |
| `GET /actuator/health` | Aplicação, banco, providers disponíveis, última/próxima busca |

`/actuator/health` reporta **DOWN** quando nenhum provider está utilizável — um monitor que não
consegue perguntar nada a ninguém não está saudável. Telegram ausente é reportado mas não derruba a
saúde: coletar preços continua valendo a pena.

### Logs

Eventos com nome fixo, fáceis de filtrar: `SEARCH_STARTED`, `SEARCH_COMPLETED`, `OFFER_FOUND`,
`OFFER_REJECTED`, `PRICE_CHANGED`, `PRICE_DROP_DETECTED`, `ALERT_SENT`, `PROVIDER_ERROR`.

Toda rejeição registra o motivo com o valor concreto:

```
OFFER_REJECTED trip=tokyo-poa-2027 source=serpapi route=HND->POA BLOCKED_COUNTRY(country=Qatar code=QA airport=DOH)
OFFER_REJECTED trip=tokyo-poa-2027 source=serpapi route=NRT->POA MAX_DURATION_EXCEEDED(duration=51h 20min limit=48h)
```

---

## 17. Testes

```bash
mvn test
```

82 testes, sem rede e sem chave de API. Cobrem:

- **Datas** — ida dentro/fora da janela; volta chegando antes/depois do prazo; o caso em que a
  partida é antes do prazo mas a chegada é depois; a armadilha de offset (23/01 22:00 no Brasil já
  é 24/01 no Japão).
- **Rotas bloqueadas** — conexão em país bloqueado rejeita; conexão permitida passa; aeroporto
  bloqueado rejeita; volta também é verificada; rota não verificável rejeita; aeroporto desconhecido
  rejeita.
- **Preço** — £1.250 excelente · £1.400 dentro · £1.600 acima (e as bordas £1.299,99/£1.300/£1.500).
- **Duração** — 25h excelente · 35h aceitável · 40h longo · 50h rejeitado.
- **Ranking** — £1.400/1 escala/25h vence £1.250/4 escalas/45h; bagagem e proximidade da data
  desempatam.
- **Queda de preço** — £10 não alerta · £50 alerta · novo mínimo alerta · novo mínimo de £1 não
  alerta · cooldown segura e depois libera.
- **Telegram** — API stubada com WireMock: envio, erro da API, falha de transporte, não configurado,
  mensagem longa demais.
- **Providers** — parsing contra payloads no formato real das duas APIs, incluindo resolução de
  fuso a partir do aeroporto de partida e a garantia de que o Travelpayouts nunca finge saber as
  conexões.
- **Pipeline completo** — `@SpringBootTest` contra H2 com a migração Flyway real: busca, filtra
  (o itinerário mais barato, via Doha, é rejeitado), salva, acumula histórico, deduplica entre
  providers e notifica.

---

## Licença

Uso pessoal. Respeite os termos de uso de cada API que você conectar.
