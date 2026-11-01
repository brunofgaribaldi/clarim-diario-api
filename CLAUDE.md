# CLAUDE.md — Clarim Diário (API)

## 1. Visão geral do projeto

Backend do **Clarim Diário**, jornal digital com **paywall**: notícia marcada como
`premium` só tem o corpo do texto entregue a quem possui assinatura recorrente vigente,
mensal ou anual, cobrada pelo Stripe. Esta API é a única dona da decisão "este leitor
pode ler este texto?" e a única que grava o estado da assinatura (quem pagou é informado
pelo webhook do Stripe, não pelo navegador). O front é um SPA React, fora deste repo.

Stack, conforme `pom.xml`:

| Item | Versão |
|---|---|
| Spring Boot (parent) | `4.1.1` |
| Java | `21` |
| PostgreSQL | driver `org.postgresql` gerenciado pelo Boot; banco `postgres:17-alpine` no compose |
| Flyway | `spring-boot-starter-flyway` + `flyway-database-postgresql` (versões gerenciadas pelo Boot) |
| Stripe SDK (`stripe-java`) | `33.4.2` |
| JJWT (`jjwt-api`/`impl`/`jackson`) | `0.12.6` |
| Google API Client | `2.7.0` |

Também em uso: starters `security`, `validation`, `webmvc`, `data-jpa`, `actuator` e
`devtools`. **Não** há Lombok, springdoc/OpenAPI nem configuração de CI.

## 2. Comandos essenciais

Segredos vêm do `.env` na raiz (copie de `.env.example`), lido pelo `docker compose`
(`env_file`) e pelo Spring (`spring.config.import=optional:file:.env[.properties]`).
**A aplicação não sobe sem `STRIPE_SECRET_KEY` e `STRIPE_WEBHOOK_SECRET`**: a primeira é
checada em `ConfiguracaoStripe.configurar()` (`IllegalStateException` no boot) e nenhuma
das duas tem padrão em `application.properties` — placeholder não resolvido quebra o boot.

| O que | Comando (Windows / Linux-macOS) |
|---|---|
| Rodar local | `.\mvnw.cmd spring-boot:run` / `./mvnw spring-boot:run` |
| Rodar os testes | `.\mvnw.cmd test` |
| Rodar um único teste | `.\mvnw.cmd test -Dtest=ApiApplicationTests#contextLoads` |
| Empacotar o `.jar` | `.\mvnw.cmd -DskipTests package` (gera `target/api-0.0.1-SNAPSHOT.jar`) |
| Subir API + Postgres | `docker compose up --build` (API em `:8080`, banco em `:5433`) |
| Derrubar preservando dados | `docker compose down` |
| Derrubar **apagando** o banco | `docker compose down -v` |
| Health check | `curl http://localhost:8080/actuator/health` |

Sem Docker, os padrões apontam para `jdbc:postgresql://localhost:5432/clarim_diario`
(`postgres`/`root`). `.\mvnw.cmd test` exige Postgres acessível e as chaves do Stripe —
o único teste existente sobe o contexto inteiro.

## 3. Arquitetura e fluxo de uma requisição

`HTTP → CorsFilter → JwtAuthenticationFilter → autorização → @RestController → @Service → JpaRepository → PostgreSQL`

1. `JwtAuthenticationFilter` lê `Authorization: Bearer <jwt>`, valida com `JwtService`,
   carrega o `Usuario` pelo e-mail do `subject` e põe um `UsuarioAutenticado` no
   `SecurityContext`; sem header, segue adiante **sem** autenticar (rotas públicas).
2. `ConfiguracaoSeguranca` decide se a rota aceita anônimo, exige login ou exige papel.
3. O controller traduz HTTP ↔ Java (`@Valid` no corpo, `@AuthenticationPrincipal` para a
   identidade) sem nenhuma regra de negócio; o service concentra a regra, usa
   repositórios e **monta o DTO de saída**.

**Entidade JPA nunca é serializada para o cliente.** Toda resposta é um `record` do pacote
`dto` (ou `exception.ErroResposta`) — `Usuario` carrega `senhaHash` e `stripeCustomerId`, e
os relacionamentos são `LAZY`: devolver a entidade vazaria dados e/ou estouraria em `LazyInitializationException`.

| Pacote | Responsabilidade |
|---|---|
| `config` | `ConfiguracaoSeguranca` (filter chain, CORS, BCrypt) e `ConfiguracaoStripe` (`Stripe.apiKey`) |
| `controller` | Borda HTTP: mapeamento, validação, extração do principal |
| `dto` | `record`s de entrada e saída — o contrato com o front |
| `exception` | Exceções de domínio + `TratadorGlobalDeExcecoes` + `ErroResposta` |
| `model` | Entidades JPA e enums (`Papel`, `Provider`, `Intervalo`, `StatusAssinatura`) |
| `repository` | Interfaces `JpaRepository`, com derived queries |
| `security` | JWT (`JwtService`, filtro), `UsuarioAutenticado`, `UserDetailsService`, validação do token Google |
| `service` | Regra de negócio: paywall, checkout, sincronização do Stripe, cadastro |

## 4. Convenções do código

**Idioma.** Domínio, classes, métodos, campos e mensagens em **português**
(`ConfiguracaoSeguranca`, `criarSessao`, `temAcessoPremium`, `periodoFim`). Inglês só onde
a API do framework ou do Stripe obriga (`@GetMapping`, `findByEmail`, nomes de eventos).

**Sufixo de DTO.** Hoje convivem quatro padrões: `...Request` (`CheckoutRequest`,
`LoginRequest`), `...Response` (`NoticiaResponse`), `...Resposta` (`LoginResposta`,
`PlanoResposta`, `NoticiaResposta`) e `...Resumo` (`NoticiaResumo`, `UsuarioResumo`), além
de `UsuarioCadastro`. **Padrão daqui pra frente: `...Request` para entrada e `...Resposta`
para saída** — não crie novas variantes (`...Response`, `...Dto`, `...Output`); ao tocar em
`NoticiaResponse`, consolide em `NoticiaResposta`.

**Injeção de dependência.** Sempre por **construtor**, campos `private final`, sem
`@Autowired` em lugar nenhum. Configuração entra como `@Value` em parâmetro de construtor
(`CheckoutService`, `WebhookController`); os `@Value` em campo que ainda existem
(`ConfiguracaoSeguranca`, `JwtService`, `ConfiguracaoStripe`) são a exceção.

**DTOs.** Sempre `record`, com Jakarta Validation no próprio record (`@NotBlank`,
`@NotNull`, `@Email`, `@Size`, `@Positive`), `@RequestBody @Valid` no controller e
mensagem de validação em português.

**Entidades.** POJOs com construtor sem argumentos e getters/setters explícitos (sem
Lombok); relacionamentos sempre `fetch = FetchType.LAZY`; colunas preenchidas pelo banco
(`criado_em`, `criada_em`, `atualizado_em`) com `insertable = false` (e `updatable = false`
quando imutáveis); `@Enumerated(EnumType.STRING)` sempre — nunca ordinal, porque o banco
tem `CHECK (... IN ('ACTIVE', ...))`.

**Erros.** Exceção de domínio em `exception` + `@ExceptionHandler` no
`TratadorGlobalDeExcecoes`, devolvendo `ErroResposta(mensagem)` — o front lê
`erro.response.data.mensagem`, então **o nome do campo é contrato**. Proibido:
`RuntimeException` crua, exceção sem mensagem, stack trace ou texto técnico chegando ao
cliente. Exceção de domínio nova = handler na mesma entrega.

**Comentários.** Os comentários didáticos longos (Dockerfile, `application.properties`,
migrations, `WebhookController`, `NoticiaService`, `CheckoutService`) são documentação:
**mantenha e atualize junto com o código**; nunca apague em refactor nem deixe um
comentário descrevendo comportamento que você mudou.

## 5. Segurança — invariantes que NÃO podem ser quebradas

- **Stateless:** `SessionCreationPolicy.STATELESS`, CSRF desabilitado, autenticação só
  por `Authorization: Bearer <jwt>`. Não introduza sessão, cookie de auth ou `rememberMe`.
- **Rota nova nasce autenticada** — `anyRequest().authenticated()` fecha a cadeia. Torná-la
  pública é decisão explícita: `permitAll` com comentário dizendo *por que*, linha nova na tabela abaixo e justificativa no PR.
- Rotas públicas hoje (`ConfiguracaoSeguranca`):

  | Método | Caminho | Por que é pública |
    |---|---|---|
  | qualquer | `/api/auth/**` | Login por senha e por Google — quem chama ainda não tem token |
  | `POST` | `/api/usuarios` | Cadastro de nova conta |
  | qualquer | `/api/webhook/**` | Chamado pelo servidor do Stripe, que não possui JWT nosso |
  | `GET` | `/api/planos` | Vitrine de preços; só *criar* o checkout exige login |
  | `GET` | `/api/noticias/**` | Listar e abrir notícia; o paywall é aplicado no dado, não na rota |
  | `GET` | `/actuator/health` | Health check do compose e da plataforma de deploy |

  Rota com papel: `POST /api/noticias` → `EDITOR` ou `ADMIN` (veja a dívida #2 — o
  controller expõe outro caminho).
- **Webhook do Stripe:** público por necessidade, e sua segurança é
  `Webhook.constructEvent(corpo, assinatura, segredoWebhook)` — HMAC sobre o corpo
  **cru**. O parâmetro é `@RequestBody String` e assim permanece: nunca troque por DTO,
  nunca desserialize ou valide antes da verificação, nunca adicione filtro/interceptor
  que leia ou reescreva o body dessa rota.
- **Conteúdo premium é autorizado no nível do dado:** `NoticiaService.buscarPorId` envia
  `texto = null` e `bloqueada = true` quando o leitor não tem direito — não é "esconder a
  tela no front" nem bloquear a rota. Todo endpoint novo que devolva corpo de notícia
  passa pela mesma decisão.
- **Identidade vem do principal autenticado** (`@AuthenticationPrincipal UsuarioAutenticado`
  → `getUsuario().getId()`, como em `AssinaturaController`). Nunca aceite id, e-mail ou
  papel vindo do corpo/query para decidir autoria ou permissão — é o defeito #3 da seção 9.
- **Segredos** nunca em código, log ou mensagem de erro; só via variável de ambiente /
  `.env` (fora do Git e do contexto de build Docker). `senhaHash` nunca entra em DTO.
- **CORS** vem de `app.cors.origens-permitidas` (`CORS_ORIGENS`) com
  `setAllowedOriginPatterns`: não hardcode origem, não use `"*"` com `allowCredentials(true)`.
- Senha com `BCryptPasswordEncoder(12)`; contas Google têm `senhaHash` nulo de propósito — login por senha jamais pode validar para elas.

## 6. Banco de dados e migrations

- **Flyway é o dono do schema** (`src/main/resources/db/migration`); o Hibernate roda com
  `ddl-auto=validate`, só confere e **derruba o boot** se entidade e tabela divergirem.
  Nunca mude para `update`/`create` para "resolver" erro de validação — corrija a entidade
  ou escreva a migration.
- **Migration aplicada é imutável** (o Flyway guarda checksum e recusa subir se um arquivo
  antigo mudar) e **toda mudança de entidade exige migration na mesma entrega**.
- Numeração segue o padrão da pasta, `V<n>__descricao_em_snake_case.sql`: hoje há
  `V1__schema_inicial.sql`, `V2__dados_referencia.sql` e `V3__noticias_demo.sql` — o
  próximo é `V4__...`.
- **Baseline** (`baseline-on-migrate=true`, `baseline-version=3`): num banco que já tem
  tabelas e nenhum histórico (o Postgres local original) o Flyway apenas anota "estou na
  versão 3" e **não executa** V1–V3; num banco vazio (Docker, Railway) roda as três. Logo,
  **correção dentro de V1–V3 nunca chega ao banco local** — schema só se propaga de V4 em diante.
- Ao inserir dados com id explícito, ajuste a sequence com `setval()`, como V2 e V3.

## 7. Integração com Stripe

1. **Vitrine** — `GET /api/planos` (público) lista `Plano` ativos com preço formatado.
2. **Checkout** — `POST /api/assinaturas/checkout` (autenticado) → `CheckoutService.criarSessao`:
   bloqueia se já há assinatura vigente (`AssinaturaJaAtivaException` → 409), obtém/cria o
   `Customer`, cria uma `Session` em modo `SUBSCRIPTION` com o `stripePriceId` do plano e
   devolve a URL hospedada. **Nenhum dado de cartão passa por esta API.**
3. **Pagamento** — o usuário paga na página do Stripe e volta para
   `${app.front-url}/assinatura/sucesso` ou `/assinatura/cancelada`. **Esse retorno não
   libera nada**: é só o navegador, pode ser fechado, perdido ou forjado.
4. **Webhook** — `POST /api/webhook/stripe` valida o HMAC e chama
   `StripeSincronizacaoService.processar`, que trata `checkout.session.completed` (só se
   houver `subscription`), `customer.subscription.updated` e `...deleted`.
5. **Liberação do acesso — aqui, e somente aqui:** `sincronizar()` grava `status` e
   `periodoFim` em `assinatura`, a linha que
   `existsByUsuarioIdAndStatusInAndPeriodoFimAfter` consulta.
6. **Leitura** — `GET /api/noticias/{id}` decide `podeLer` por essa assinatura (ou pelo
   papel `EDITOR`/`ADMIN`) e só então inclui `texto` no JSON.

Propriedades que **precisam ser preservadas** nesse fluxo:

- **Idempotência do upsert**: `findByStripeSubscriptionId(...).orElseGet(nova)` — o Stripe
  reenvia eventos e reprocessar não pode duplicar assinatura.
- **Releitura na API do Stripe**: `Subscription.retrieve(id)` em vez de confiar no payload
  do evento — a ordem de entrega não é garantida e evento antigo não pode sobrescrever
  estado novo.
- **200 mesmo para evento ignorado ou de cliente desconhecido**: erro na resposta faz o
  Stripe retentar por dias; falha de comunicação é logada, não relançada, e só assinatura
  HMAC inválida responde 400.
- `Customer` é criado uma vez por usuário e o id é persistido com `save()` explícito — não há `@Transactional` nessa cadeia.

## 8. Testes

**Estado atual: praticamente inexistente.** O único arquivo em `src/test` é
`ApiApplicationTests` com um `contextLoads()` vazio (`@SpringBootTest`), que só prova que o
contexto sobe — e exige Postgres acessível e as chaves do Stripe. Não há teste de unidade,
de controller (`@WebMvcTest`), de repositório (`@DataJpaTest`) nem de segurança.

Cobertura mínima esperada daqui pra frente, nas regras sensíveis: liberação de conteúdo
premium (`NoticiaService.buscarPorId` — anônimo, leitor sem assinatura, com assinatura
vigente, expirada, `EDITOR`/`ADMIN`); bloqueio de assinatura duplicada (`CheckoutService` →
`AssinaturaJaAtivaException`); validação de assinatura do webhook (válida processa, inválida
responde 400 e **não** toca o banco) e idempotência do upsert; autorização por papel para
criar notícia e rejeição de token ausente/expirado.

Dependências de teste **já disponíveis** no `pom.xml`: `spring-boot-starter-webmvc-test`,
`-data-jpa-test`, `-validation-test` e `-security-test` (JUnit 5, AssertJ, Mockito e `MockMvc`
por transitividade). Não adicione novas (Testcontainers, WireMock…) sem pedir.

## 9. Dívida técnica conhecida

Esta seção existe para você **não repetir** estes erros.

| # | Sev. | Arquivo | Problema |
|---|---|---|---|
| 1 | **Crítica** | `service/NoticiaService.java` (`paraDto`) | `NoticiaResumo` recebe o `texto` **completo**, inclusive de notícias `premium`, e `GET /api/noticias` é público → paywall contornável pela listagem |
| 2 | **Alta** | `config/ConfiguracaoSeguranca.java` + `controller/NoticiaController.java` | A regra protege `POST /api/noticias` (`EDITOR`/`ADMIN`), mas o controller expõe `POST /api/noticias/criar` → cai em `anyRequest().authenticated()` e a checagem de papel nunca roda no Security |
| 3 | **Alta** | `service/NoticiaService.java` (`criar`) | Autor vem de `NoticiaRequest.autorId()` (corpo), não do token; a validação de papel confere o papel do *autor informado*, não de quem chamou |
| 4 | **Alta** | `controller/AuthController.java` (`login`) | `catch (Exception) -> throw new RuntimeException` mascara credencial inválida como **500** em vez de 401 e engole a causa real |
| 5 | Média | `security/JwtAuthenticationFilter.java` | `extrairEmail` sem try/catch: token expirado ou mal assinado lança `JwtException` dentro do filtro → 500 em vez de 401 |
| 6 | Média | `service/UsuarioService.java` | `throw new IllegalArgumentException()` sem mensagem e sem handler → e-mail duplicado vira 500 |
| 7 | Média | `service/GoogleAuthService.java` (`vincularPorEmail`) | Altera `providerId` sem `save()` e sem `@Transactional` (mudança perdida) e não ajusta `provider` para `GOOGLE` |
| 8 | Média | `service/NoticiaService.java`, `controller/PlanoController.java` | Listagens sem paginação (`findAll()`, `findByAtivoTrue()`) — sem `Pageable`, sem limite |
| 9 | Média | `dto/` | DTOs redundantes: `NoticiaResponse` vs. `NoticiaResposta` quase idênticos; `UsuarioResumo` sem nenhum uso; imports `java.util.Set` mortos em `NoticiaRequest`/`NoticiaResponse` |
| 10 | Média | `dto/NoticiaRequest.java`, `dto/UsuarioCadastro.java` | Validação em conflito com o schema: `titulo` máx. 100 vs. `varchar(200)`; `slug` máx. 100 vs. `varchar(220)`; `resumo` sem `@Size` vs. `varchar(400)`; `email` sem `@Size` vs. `varchar(180)` |
| 11 | Média | `model/Usuario.java`, `model/Assinatura.java`, `model/Noticia.java` | Entidade divergente do DDL: `email` `length=120` vs. `varchar(180)`; `senhaHash` `length=80` vs. `varchar(72)`; `periodoFim` `nullable=false` vs. coluna anulável; coluna `noticia.imagem_url` sem campo correspondente |
| 12 | Baixa | `security/UserDetailsService.java`, `model/Usuario.java`, `model/Noticia.java`, `model/Endereco.java`, `repository/TagRepository.java`, `service/CheckoutService.java` | Arestas acumuladas: `UserDetailsService` tem o nome da própria interface do Spring e campo injetado não-`final`; método vazio `setSenhaA()`; campo `usuario` mapeando `autor_id` (nome engana); `Endereco` e `TagRepository` sem uso algum; `catch (StripeException) -> throw new RuntimeException(e)` sem handler, virando 500 opaco |

## 10. Ao trabalhar neste repositório

- Escreva em **português** (identificadores, mensagens, comentários); inglês só na API do framework.
- Respeite as camadas: controller fala HTTP, service tem a regra, repository fala banco.
- **Nunca** devolva entidade JPA — sempre DTO `record`, sufixo `...Request`/`...Resposta`.
- Mudou entidade? **Crie a migration `V<n>__...` na mesma entrega.** Nunca edite V1–V3.
- Criou rota? Confira `ConfiguracaoSeguranca` **antes de concluir**: ela nasce autenticada, e pública só com justificativa escrita e linha nova na tabela da seção 5.
- Identidade e permissão vêm do token, nunca do corpo da requisição.
- Erro novo = exceção de domínio + handler no `TratadorGlobalDeExcecoes`; nunca `RuntimeException` crua, nunca stack trace para o cliente.
- Não mexa no `@RequestBody String` do webhook do Stripe.
- Rode `.\mvnw.cmd test` antes de concluir e diga o resultado (inclusive se falhar por falta de banco/chaves).
- **Não introduza Lombok** nem dependência nova sem pedir; **não altere a versão do Spring
  Boot** (`4.1.1`) nem o `java.version` (`21`).
- Mantenha os comentários explicativos existentes atualizados; não os apague.
