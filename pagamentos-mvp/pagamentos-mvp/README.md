# Pagamentos MVP (Java 21, zero dependências)

Núcleo técnico de uma intermediadora de pagamentos, rodando em **sandbox** (adquirente simulado, sem dinheiro real).

## Rodar
```bash
./build.sh test        # compila e roda os 15 testes
./build.sh run 8080    # sobe a API (PAY_API_KEY=dev-key por padrão)
```

## Exemplo
```bash
H='Authorization: Bearer dev-key'
curl -X POST localhost:8080/v1/merchants -H "$H" -d '{"id":"loja","name":"Loja","feeBps":299,"fixedFeeCents":30}'
curl -X POST localhost:8080/v1/payments  -H "$H" -H 'Idempotency-Key: pedido-123' \
  -d '{"merchantId":"loja","amount":15000,"method":"CARD","installments":3,"token":"tok_visa"}'
curl -X POST localhost:8080/v1/settlements/run -H "$H" -d '{"date":"2099-01-01"}'
curl localhost:8080/v1/merchants/loja/balance -H "$H"
curl localhost:8080/v1/ledger/integrity -H "$H"
```
Valores em **centavos inteiros**. Cartão com `"token":"tok_decline"` é recusado (HTTP 402).

## Endpoints
| Método | Rota | O que faz |
|---|---|---|
| POST | /v1/merchants | cadastra lojista (taxa em bps + fixa, webhookUrl opcional) |
| POST | /v1/payments | cobrança PIX/CARD(1-12x)/BOLETO com split; header `Idempotency-Key` obrigatório |
| GET | /v1/payments/{id} | pagamento + agenda de recebíveis |
| POST | /v1/payments/{id}/refund | estorno total (idempotente) |
| GET | /v1/merchants/{id}/balance | saldo pendente e disponível |
| POST | /v1/settlements/run | liquida recebíveis vencidos até a data |
| GET | /v1/reconciliation | cruza pagamentos com o arquivo do adquirente |
| GET | /v1/ledger/integrity | verifica que a soma de todos os saldos é 0 |

## Arquitetura
- `Ledger`: dupla entrada, append-only, cada transação soma zero.
- `PaymentService`: idempotência, split (resto ao lojista principal, fecha ao centavo), agenda D+0 / D+1 / D+30n, liquidação, estorno.
- `Acquirer`: interface; `Acquirer.Fake` simula. Trocar por Pagar.me/Asaas/etc. não altera o resto.
- `Webhooks`: outbox + HMAC-SHA256 + backoff exponencial + dead letter + verificação anti-replay.
- `Reconciliation`: valor divergente, só de um lado, status divergente.

## O que este MVP NÃO é (limites honestos)
- **Em memória**: reiniciou, perdeu tudo. Falta PostgreSQL com transações e `UNIQUE` na chave de idempotência.
- **Sem dinheiro real**: não há integração bancária, repasse (payout/saque), antecipação, chargeback nem disputa.
- **Sem PCI DSS**: nunca receba número de cartão; use tokenização do parceiro.
- **Sem autorização do BC**, KYC/PLD real, antifraude ou LGPD.
- Autenticação é uma chave única; produção precisa de chaves por lojista, rotação e rate limit.
- Webhook URL: só valida o esquema; produção deve bloquear IPs privados (SSRF).
- Um único lock global: correto, mas não escala. Em produção, locks por linha no banco.

## Próximos passos sugeridos
1. Migrar para Spring Boot + PostgreSQL + Flyway (o domínio já está isolado).
2. Persistir ledger e outbox na mesma transação do pagamento.
3. Integrar um PSP real em sandbox atrás da interface `Acquirer`.
4. Payout (saque) com saldo reservado, chargeback e estorno parcial.

## Hospedar na internet (Render, plano gratuito)
Variáveis de ambiente lidas pela API:

| Variável | Para quê |
|---|---|
| `PORT` | porta (o Render define sozinho) |
| `BIND_HOST` | `0.0.0.0` no Docker; padrão `127.0.0.1` (só local) |
| `PAY_API_KEY` | chave da API. Fora do localhost exige 16+ caracteres, senão a API não sobe |
| `PAY_WEBHOOK_SECRET` | segredo da assinatura dos webhooks |
| `PAY_ALLOWED_ORIGIN` | endereço exato do app (CORS). Vazio = CORS desligado |

Arquivos: `Dockerfile`, `render.yaml` (blueprint), `.dockerignore`.
Atenção: no plano gratuito o serviço dorme após 15 min sem uso (~1 min para acordar) e **os dados em memória somem** a cada reinício. Serve para demonstração, não para dinheiro real.
