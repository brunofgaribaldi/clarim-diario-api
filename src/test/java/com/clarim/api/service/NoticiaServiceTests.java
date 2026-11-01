package com.clarim.api.service;

import com.clarim.api.dto.NoticiaResumo;
import com.clarim.api.dto.NoticiaResposta;
import com.clarim.api.exception.RecursoNaoEncontradoException;
import com.clarim.api.model.Categoria;
import com.clarim.api.model.Noticia;
import com.clarim.api.model.Papel;
import com.clarim.api.model.Usuario;
import com.clarim.api.repository.AssinaturaRepository;
import com.clarim.api.repository.CategoriaRepository;
import com.clarim.api.repository.NoticiaRepository;
import com.clarim.api.repository.UsuarioRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NoticiaServiceTests {

    @Test
    void listarNuncaEntregaTextoCompletoNoResumo() {
        NoticiaRepository noticiaRepository = mock(NoticiaRepository.class);
        AssinaturaRepository assinaturaRepository = mock(AssinaturaRepository.class);
        CategoriaRepository categoriaRepository = mock(CategoriaRepository.class);
        UsuarioRepository usuarioRepository = mock(UsuarioRepository.class);
        NoticiaService service = new NoticiaService(
                noticiaRepository, categoriaRepository, usuarioRepository, assinaturaRepository);

        Noticia premium = noticia(true);
        Noticia gratuita = noticia(false);
        when(noticiaRepository.findAll()).thenReturn(List.of(premium, gratuita));

        List<NoticiaResumo> noticias = service.listarTodas();

        assertEquals(2, noticias.size());
        assertTrue(noticias.stream().allMatch(noticia -> noticia.texto() == null));
        verifyNoInteractions(assinaturaRepository);
    }

    @Test
    void visitanteRecebeMateriaPremiumBloqueadaSemTexto() {
        NoticiaRepository noticiaRepository = mock(NoticiaRepository.class);
        AssinaturaRepository assinaturaRepository = mock(AssinaturaRepository.class);
        NoticiaService service = new NoticiaService(
                noticiaRepository, mock(CategoriaRepository.class),
                mock(UsuarioRepository.class), assinaturaRepository);
        when(noticiaRepository.findById(1L)).thenReturn(Optional.of(noticia(true)));

        NoticiaResposta resposta = service.buscarPorId(1L, null);

        assertNull(resposta.texto());
        assertTrue(resposta.bloqueada());
        verifyNoInteractions(assinaturaRepository);
    }

    @Test
    void materiaGratuitaContinuaDisponivelParaVisitante() {
        NoticiaRepository noticiaRepository = mock(NoticiaRepository.class);
        NoticiaService service = new NoticiaService(
                noticiaRepository, mock(CategoriaRepository.class),
                mock(UsuarioRepository.class), mock(AssinaturaRepository.class));
        when(noticiaRepository.findById(1L)).thenReturn(Optional.of(noticia(false)));

        NoticiaResposta resposta = service.buscarPorId(1L, null);

        assertEquals("Conteúdo completo", resposta.texto());
        assertFalse(resposta.bloqueada());
    }

    private Noticia noticia(boolean premium) {
        Categoria categoria = new Categoria();
        categoria.setNome("Cidade");

        Usuario autor = new Usuario();
        autor.setNome("Redação");
        autor.setPapel(Papel.EDITOR);

        Noticia noticia = new Noticia();
        noticia.setId(1L);
        noticia.setTitulo("Notícia de teste");
        noticia.setSlug("noticia-de-teste");
        noticia.setResumo("Resumo da notícia");
        noticia.setTexto("Conteúdo completo");
        noticia.setPremium(premium);
        noticia.setCategoria(categoria);
        noticia.setUsuario(autor);
        return noticia;
    }
}
