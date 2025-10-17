# NidusCare Backend (Firebase Cloud Functions)

Este diretório contém todo o código de backend (server-side) do aplicativo NidusCare. Ele é escrito em JavaScript (Node.js) e implantado no ambiente do Firebase Cloud Functions.

## Principais Responsabilidades

* **Lógica de Negócios Segura:** Executa lógica que não pode ser exposta no aplicativo cliente, como verificações de permissão premium.
* **Integração com IA (Gemini):** Hospeda as funções (`onCall`) que se comunicam com a API da Vertex AI (Gemini 1.5 Pro) para:
    * Análise de receitas médicas (`analisarReceita`).
    * Análise de caixas de remédios (`analisarCaixaRemedio`).
    * Geração de relatórios e insights (`gerarAnalisePreditiva`).
* **Tarefas Agendadas (`onSchedule`):** Executa rotinas automáticas para monitorar a saúde do dependente, como:
    * `checkMissedDoses`: Verifica se há doses atrasadas.
    * `checkUpcomingSchedules`: Envia lembretes de agendamentos futuros.
    * `checkUpcomingExpiries`: Alerta sobre lotes de medicamentos próximos do vencimento.
    * `sendDailySummary`: Envia um resumo diário para os cuidadores.
* **Gatilhos de Banco de Dados (`onDocumentWritten`):**
    * `onDataWritten`: Popula a `timeline` unificada do dependente automaticamente quando novos dados de saúde são registrados.
    * `checkLowStock`: Monitora alterações em medicamentos e dispara alertas de estoque baixo.

## Tech Stack

* **Ambiente:** Node.js
* **Provedor:** Firebase Cloud Functions (v2)
* **Banco de Dados:** Cloud Firestore
* **IA:** Google Vertex AI (Gemini 1.5 Pro)
* **Autenticação:** Firebase Authentication

## Implantação (Deploy)

Para implantar novas alterações neste backend, use o Firebase CLI no terminal a partir da raiz do projeto (não de dentro da pasta `functions`):

```bash
firebase deploy --only functions