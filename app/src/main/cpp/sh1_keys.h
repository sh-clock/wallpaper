#ifndef SH1_KEYS_H
#define SH1_KEYS_H
/* Verbatim copy of the LiveKey enum inside fog_poc.c.
 * Kept in sync manually — the JNI layer needs the numeric values to
 * inject UI events into the engine. If fog_poc.c's enum changes,
 * update this copy. */
typedef enum {
    K_NONE = 0, K_ESC, K_QUIT, K_SPACE, K_R, K_F, K_P, K_S, K_N,
    K_LEFT, K_RIGHT, K_PLUS, K_MINUS, K_M, K_B,
    K_W, K_L,     /* v8: W = weather cycle (snow/rain/off), L = lightning */
    K_D,          /* v15: D = ordered dither toggle (default ON) */
    K_TAB, K_UP, K_DOWN, K_ENTER, K_LBRK, K_RBRK, K_COMMA, K_PERIOD,
    K_H, K_D0, K_D1, K_D2, K_D3, K_D4, K_D5, K_D6, K_D7, K_D8, K_D9,
    K_VIEW, K_R2TOGGLE
} LiveKey;

#endif /* SH1_KEYS_H */
