# Plataforma de skills — LFM3Local

**Decisão de produto (2026-11-23):** inspirado em recursos de assistente offline, extensibilidade por mini-apps e gestão de memória do Layla, **sem roleplay, personagens, simuladores de companhia ou personalidades ficcionais**. LFM3Local será uma plataforma local de assistência séria e funcional, com o LFM3-1.7B-Q4_K_M.gguf como motor-alvo.

## 1. Separação entre núcleo e domínios

**Núcleo genérico:** inferência JNI/llama.cpp, formatação de prompts LFM3, histórico, memória com controles do usuário, orquestração, descoberta de ferramentas, permissões, observabilidade, diagnósticos e configuração.

**Skill:** módulo de capacidade contextual ativável/desativável que define instruções de tarefa, ferramentas permitidas, fontes/índices de conhecimento, limites, permissões e testes próprios. Skills não modificam o motor nem são incorporadas ao prompt global permanentemente.

**Dados de domínio:** coletâneas, documentos, bibliotecas ou índices importados sob licença adequada. Os dados ficam isolados por skill e com identificação da origem.

## 2. Experiência de uso

- Tela principal abre diretamente em chat; interação com uma IA assistente, sem selecionar personagem.
- Perfis operacionais (ex.: Padrão, Preciso, Exploração) ajustam parâmetros e instruções **sem fingir ser personalidades diferentes**.
- Catálogo de Skills: instalar (inicialmente embutidas), habilitar/desabilitar, ver permissões, configurar dados, testar e consultar logs.
- Detecção de intenção sugere usar uma skill; em tarefas sensíveis ou ambíguas, confirmar antes de ativar.
- Usuário pode forçar uma skill, ou conversar sem nenhuma skill. O chat continua funcional se uma skill falhar.
- Interface limpa: Chat | Skills | Atividade | Configurações/Diagnóstico.
- Memória geral e memória de skill separadas, com controles para visualizar, editar e apagar dados.
- Exibir estados reais do motor e das ferramentas, jamais uma atividade fictícia.
- Funcionalidade principal opera offline. Qualquer recurso de rede exige habilitação explícita.

## 3. Contrato técnico proposto (P2/P3)

`SkillManifest`: `id`, `version`, `name`, `description`, `triggerHints`, `instructions`, `tools`, `dataSources`, `permissions`, `limits`, `evaluationSuite`.

`SkillRegistry`: catálogo e estado ativado/desativado.

`SkillRouter`: roteamento determinístico + interpretação LLM quando justificável; skill explícita tem prioridade; sem acionamento silencioso de permissões extras.

`SkillRuntime`: injeta instruções e schemas **apenas para a skill ativa**; executa ferramentas no `ToolRegistry` com validação, timeout, limites e logs.

`SkillDataProvider`: acesso read-only a documentos/indexação sob escopo da skill; não concede acesso arbitrário ao armazenamento Android.

`SkillPermissionPolicy`: controle granular por ferramenta, confirmação de efeitos colaterais e lista explícita de capacidades.

`SkillEvaluation`: conjuntos de casos com entradas, resultados verificáveis, critérios de robustez e testes de regressão.

No primeiro momento, o código das skills estará compilado no APK e os manifestos serão declarativos. Não carregar código executável de terceiros no MVP. Futuramente, admitir instalação de pacotes assinados de configurações e dados; a execução dinâmica de código só será considerada após projeto de sandbox, auditoria e permissões.

### Exemplo de manifesto conceitual

```yaml
id: br.hinos.contextuais
version: 0.1.0
name: Sugestão contextual de hinos
activation: explicit-or-confirmed
data_sources:
  - hymn_collection_index
tools:
  - search_hymns
  - get_hymn
  - refine_hymn_results
permissions:
  internet: false
  local_collection: read
  side_effects: none
```

## 4. Skill futura: recomendação contextual de hinos

**Objetivo:** receber uma descrição livre (tema, anotação espiritual, passagem bíblica, propósito de culto, restrições do usuário), consultar a coletânea **real** e sugerir hinos com justificativas ancoradas em trechos e metadados recuperados.

Fluxo: `interpretar contexto -> recuperar candidatos -> avaliar pertinência temática -> justificar com evidências -> conversar e refinar seleção`.

Ferramentas específicas:
- `search_hymns(query, filters, limit)`: busca local, com IDs estáveis, títulos, fonte e evidências.
- `get_hymn(id)`: consulta ao registro de hino.
- `refine_hymn_results(add_ids, remove_ids, constraints)`: acrescenta/remove sugestões, mantendo o estado.
- `search_scripture(query)`: opcional, consulta a corpus bíblico local com licença adequada.

Critérios de qualidade:
- Relevância **semântica/contextual**, não somente coincidência de palavras (ex.: parábola do filho pródigo requer distinguir retorno e arrependimento de menções genéricas a 'filho' ou 'céu').
- Não inventar número de hino, título, letra nem atribuir trechos sem prova do acervo.
- Conversa multi-turno que respeita preferências e permite retirar/adicionar hinos específicos.
- Quando faltarem dados da coletânea, apresentar a dependência em vez de sugerir itens inexistentes.
- Testes com contextos reais, gabarito de pertinência humana e taxa de falsos positivos.
- Sem dados religiosos nem prompts de hinos carregados no chat geral quando a skill estiver inativa.

## 5. Fases e critérios de aceite

**P0–P1:** baseline de inferência + primeiro APK; não depende de skills nem coletânea.

**P2:** ToolRegistry, ToolExecutor e estrutura `SkillManifest/Registry/Router`, mais skill simples de testes (por exemplo, calculadora); contrato e isolamento funcionais.

**P3:** memória e busca de documentos com escopos de dados por skill, filtros e limites de contexto.

**P6 — futuro, independente:** importar coletânea autorizada, construir índice, implementar ferramentas de hinos, avaliações e UX própria.

Aceite:
- [ ] O núcleo funciona sem nenhuma skill instalada/ativa.
- [ ] Ativar/desativar uma skill não recompila o motor nem reinicia o modelo à força.
- [ ] Falha de uma skill não impede conversação geral.
- [ ] Tools são expostas apenas quando a skill está habilitada e autorizada.
- [ ] Permissões de leitura/escrita/rede são aplicadas pelo hospedeiro, não por instruções ao LLM.
- [ ] O usuário pode ver que skill, dados e ferramentas contribuíram para uma resposta.
- [ ] A skill de hinos nunca contamina a conversa fora do seu escopo.

## 6. Referências de produto

- Layla — introdução e modos: https://help.layla-network.ai/getting-started/
- Layla — mini-apps, SDK e memória: https://blog.layla-network.ai/post/layla-7-mini-apps-real-time-memory-multilingual-voices
- Layla — recursos offline e agentes: https://blog.layla-network.ai/features

**Importante:** referência de funcionalidades, não reprodução de marca, código proprietário, visuais ou personagens do Layla.
