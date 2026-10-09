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
 * 合并与装载走同一条全序：候选按 (priority, providerId, name) 排序后逐名
 * first-wins——结果与 provider 注册顺序无关（属性测试在案）；同名同 priority
 * = 两根声明同一 rank，配置错误 fail loud；跨 priority 遮蔽告警。
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

    @Override
    public SkillCatalog catalog() {
        // 判重走 merged 表（同 priority 不同名的条目会插在同名两条之间，相邻性不可依赖）
        Map<String, SkillSummary> merged = new LinkedHashMap<>();
        Map<String, Integer> winnerPriority = new LinkedHashMap<>();
        for (Entry entry : ordered()) {
            SkillSummary summary = entry.candidate().summary();
            String name = summary.name();
            Integer prevPriority = winnerPriority.get(name);
            if (prevPriority != null) {
                if (prevPriority == entry.candidate().priority()) {
                    throw new IllegalStateException("duplicate skill rank: '" + name + "' at priority "
                        + prevPriority + " (sources '" + merged.get(name).source() + "' and '"
                        + summary.source() + "')");
                }
                LOG.log(System.Logger.Level.WARNING, "skill ''{0}'' from ''{1}'' shadows ''{2}''",
                    name, summary.source(), merged.get(name).source());
                continue;
            }
            merged.put(name, summary);
            winnerPriority.put(name, entry.candidate().priority());
        }
        return new SkillCatalog(List.copyOf(merged.values()));
    }

    @Override
    public Optional<SkillDefinition> load(String name) {
        Objects.requireNonNull(name, "name");
        List<Entry> ordered = ordered();
        boolean present = ordered.stream().anyMatch(e -> e.candidate().summary().name().equals(name));
        if (!present) {
            return Optional.empty();
        }
        // 胜出者 = 全序中该名首条；委托其来源 provider——与 catalog() 同一裁决
        return ordered.stream()
            .filter(e -> e.candidate().summary().name().equals(name))
            .findFirst()
            .flatMap(e -> e.origin().provider().load(name));
    }
}
