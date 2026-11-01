package com.clarim.api.service;

import com.clarim.api.exception.RecursoNaoEncontradoException;
import com.clarim.api.model.Usuario;
import com.clarim.api.repository.AssinaturaRepository;
import com.clarim.api.repository.PlanoRepository;
import com.clarim.api.repository.UsuarioRepository;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StripeSincronizacaoServiceTests {

    @Test
    void naoSincronizaCheckoutAindaNaoPago() throws Exception {
        UsuarioRepository usuarioRepository = mock(UsuarioRepository.class);
        AssinaturaRepository assinaturaRepository = mock(AssinaturaRepository.class);
        PlanoRepository planoRepository = mock(PlanoRepository.class);
        Usuario usuario = usuario(7L, "cus_123");
        when(usuarioRepository.findById(7L)).thenReturn(Optional.of(usuario));

        Session sessao = mock(Session.class);
        when(sessao.getStatus()).thenReturn("complete");
        when(sessao.getMode()).thenReturn("subscription");
        when(sessao.getPaymentStatus()).thenReturn("unpaid");

        try (MockedStatic<Session> stripe = mockStatic(Session.class)) {
            stripe.when(() -> Session.retrieve("cs_test_123")).thenReturn(sessao);
            StripeSincronizacaoService service = new StripeSincronizacaoService(
                    usuarioRepository, planoRepository, assinaturaRepository);

            assertFalse(service.confirmarCheckout("cs_test_123", 7L));
            verifyNoInteractions(assinaturaRepository, planoRepository);
        }
    }

    @Test
    void naoPermiteConfirmarSessaoDeOutraConta() throws Exception {
        UsuarioRepository usuarioRepository = mock(UsuarioRepository.class);
        AssinaturaRepository assinaturaRepository = mock(AssinaturaRepository.class);
        PlanoRepository planoRepository = mock(PlanoRepository.class);
        Usuario usuario = usuario(7L, "cus_123");
        when(usuarioRepository.findById(7L)).thenReturn(Optional.of(usuario));

        Session sessao = mock(Session.class);
        when(sessao.getStatus()).thenReturn("complete");
        when(sessao.getMode()).thenReturn("subscription");
        when(sessao.getPaymentStatus()).thenReturn("paid");
        when(sessao.getClientReferenceId()).thenReturn("99");
        when(sessao.getCustomer()).thenReturn("cus_123");

        try (MockedStatic<Session> stripe = mockStatic(Session.class)) {
            stripe.when(() -> Session.retrieve("cs_test_123")).thenReturn(sessao);
            StripeSincronizacaoService service = new StripeSincronizacaoService(
                    usuarioRepository, planoRepository, assinaturaRepository);

            assertThrows(
                    RecursoNaoEncontradoException.class,
                    () -> service.confirmarCheckout("cs_test_123", 7L));
            verifyNoInteractions(assinaturaRepository, planoRepository);
        }
    }

    private Usuario usuario(Long id, String stripeCustomerId) {
        Usuario usuario = new Usuario();
        usuario.setId(id);
        usuario.setStripeCustomerId(stripeCustomerId);
        return usuario;
    }
}
