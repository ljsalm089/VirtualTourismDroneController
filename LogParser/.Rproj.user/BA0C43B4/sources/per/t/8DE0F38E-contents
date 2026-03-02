# install.packages("tidyverse")
# devtools::install_github("r-lib/conflicted")
# install.packages("ggpubr")
# install.packages("rstatix")

library("tidyverse")
library("ggpubr")
library("rstatix")
library("tidyr")
library("dplyr")


data1 = read.csv("Joysticks_Monitor.csv")
data2 = read.csv("Joysticks_HMD.csv")
data3 = read.csv("Headset.csv")


data_long <- bind_rows(data1 %>% select(Participant, IPQ.Score=IPQ.Score) %>% mutate(Condition="Condition #1"), data2 %>% select(Participant, IPQ.Score=IPQ.Score) %>% mutate(Condition="Condition #2"), data3 %>% select(Participant, IPQ.Score=IPQ.Score) %>% mutate(Condition="Condition #3"))

kruskal_test(IPQ.Score ~ Condition, data=data_long)

wilcox_res <- data_long %>% pairwise_wilcox_test(IPQ.Score ~ Condition, paired = FALSE, p.adjust.method = "bonferroni")

sign_res <- wilcox_res %>% filter(p.adj < 0.05) %>% mutate(p.adj.signif = '*') %>% add_xy_position(x="Condition")

summary_stats <- data_long %>% group_by(Condition) %>% summarise(mean = mean(IPQ.Score), sd = sd(IPQ.Score))

plot_chart <- ggboxplot(data_long, x = "Condition", y = "IPQ.Score", fill = "Condition", palette = "jco", color="Black", size = 1, caption='* means significant differences, where p < 0.05') + 
  stat_summary(fun = mean, geom = "point", size = 3, color="black") +
  stat_pvalue_manual(sign_res, label = "p.adj.signif", tip.length = 0.01, size = 6, label.size = 7) +
  labs(y = "IPQ Score", x = "Condition") + 
  theme(legend.position="none")

plot_chart + font("caption", size=13, color="black") + font("xlab", size=15, color="blue") + font("ylab", size=15, color="blue") + font("xy.text", size=13, color="gray", face="bold")

ggsave(
  "IPQ_Score_result.png",
  plot=get_last_plot(),
  device = NULL,
  scale = 1,
  width = 6,
  height = 4,
  units = c("in", "cm", "mm", "px"),
  dpi = 300,
)