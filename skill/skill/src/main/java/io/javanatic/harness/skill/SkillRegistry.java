package io.javanatic.harness.skill;

import io.javanatic.harness.kernel.scope.Disposable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SkillService 实现（package-private，对齐 {@code ScopedRegistry} 形态）。
 * 合并与装载走同一条胜者归并：候选按 (priority, providerId, name) 全序逐名
 * first-wins——结果与 provider 注册顺序无关（属性测试在案）；同名同 priority
 * = 两根声明同一 rank，配置错误 fail loud（catalog 与 load 两入口一致）；
 * 跨 priority 遮蔽告警（方向：胜出者遮蔽被盖者）。
 */
final class SkillRegistry implements SkillService {

    private static final System.Logger LOG = System.getLogger(SkillRegistry.class.getName());

    private final List<RegisteredProvider> providers = new CopyOnWriteArrayList<>();

    private record RegisteredProvider(String id, SkillProvider provider) {}

    /** 排序键里的胜者记录：摘要 + 来源 provider——load 委托对象由它唯一定死。 */
    private record Entry(SkillCandidate candidate, RegisteredProvider origin) {}

    @Override
    public Disposable register(SkillProvider provider) {
        Objects.requireNonNull(provider, "provider");
        if (providers.stream().anyMatch(p -> p.id().equals(provider.id()))) {
            throw new IllegalStateException("skill provider already registered: '" + provider.id() + "'");
        }
        RegisteredProvider registered = new RegisteredProvider(provider.id(), provider);
        providers.add(registered);
        return Disposable.of(() -> providers.remove(registered));
    }

    /** (priority, providerId, name) 全序拉平全部 provider 候选；与注册顺序无关。 */
    private List<Entry> ordered() {
        List<Entry> ordered = new ArrayList<>();
        for (RegisteredProvider registered : providers) {
            for (SkillCandidate candidate : registered.provider().catalog()) {
                ordered.add(new Entry(candidate, registered));
            }
        }
        ordered.sort(Comparator.comparingInt((Entry e) -> e.candidate().priority())
            .thenComparing(e -> e.origin().id())
            .thenComparing(e -> e.candidate().summary().name()));
        return ordered;
    }

    /**
     * 胜者归并（catalog 与 load 的唯一裁决点）：逐名 first-wins；同名同 priority
     * fail loud（判重走表，不依赖全序相邻——同 priority 不同名条目会插在中间）；
     * 遮蔽告警方向 = 胜出者（先入者）遮蔽被跳过者。
     */
    private Map<String, Entry> winners() {
        Map<String, Entry> winners = new LinkedHashMap<>();
        for (Entry entry : ordered()) {
            String name = entry.candidate().summary().name();
            Entry prev = winners.get(name);
            if (prev != null) {
                if (prev.candidate().priority() == entry.candidate().priority()) {
                    throw new IllegalStateException("duplicate skill rank: '" + name + "' at priority "
                        + prev.candidate().priority() + " (sources '"
                        + prev.candidate().summary().source() + "' and '"
                        + entry.candidate().summary().source() + "')");
                }
                LOG.log(System.Logger.Level.WARNING, "skill ''{0}'' from ''{1}'' shadows ''{2}''",
                    name, prev.candidate().summary().source(), entry.candidate().summary().source());
                continue;
            }
            winners.put(name, entry);
        }
        return winners;
    }

    @Override
    public SkillCatalog catalog() {
        // 契约序 = 按名（稳定面，不随 rank 数值变化）；胜者裁决序与展示序分离
        List<SkillSummary> sorted = new ArrayList<>(winners().values().stream()
            .map(e -> e.candidate().summary())
            .toList());
        sorted.sort(Comparator.comparing(SkillSummary::name));
        return new SkillCatalog(List.copyOf(sorted));
    }

    @Override
    public Optional<SkillDefinition> load(String name) {
        Objects.requireNonNull(name, "name");
        // 与 catalog 同一归并：dup-rank 配置错误在两入口同样 fail loud
        Entry winner = winners().get(name);
        if (winner == null) {
            return Optional.empty();
        }
        return winner.origin().provider().load(name);
    }
}
