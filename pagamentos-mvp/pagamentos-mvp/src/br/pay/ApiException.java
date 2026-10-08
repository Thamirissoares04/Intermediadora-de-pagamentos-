package br.pay;

/** Erro de negócio/validação que vira resposta HTTP com status e código estável. */
public class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final int status;
    public final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
