f <- function(x) {
  ifelse(x > 0, (exp(3 * x) - 1) / (exp(3) - 1), -(exp(3 * (-x)) - 1) / (exp(3) - 1))
}
curve(f, from = -1, to= 1 )
x <- seq(-1, 1, length.out=5000)
y <- f(x)
plot(x, y, type="l", xlim=c(-1, 1), ylim=c(-1, 1), xlab='joystick offset', ylab="real sent offset", asp=1, cex.lab=5)
abline(h=0, v=0, col="gray", lty=2)

ggsave(
  "joystick_offset_mapping.jpeg",
  plot=get_last_plot(),
  device = NULL,
  scale = 1,
  width = 6,
  height = 6,
  units = c("in", "cm", "mm", "px"),
  dpi = 300,
)