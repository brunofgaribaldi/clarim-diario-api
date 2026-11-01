package com.clarim.api.exception;

public class CheckoutNaoConfirmadoException extends RuntimeException {
    public CheckoutNaoConfirmadoException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
