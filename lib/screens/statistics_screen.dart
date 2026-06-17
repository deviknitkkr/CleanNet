import 'dart:async';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:fl_chart/fl_chart.dart';
import '../models/vpn_model.dart';

class StatisticsScreen extends StatefulWidget {
  const StatisticsScreen({super.key});

  @override
  State<StatisticsScreen> createState() => _StatisticsScreenState();
}

class _StatisticsScreenState extends State<StatisticsScreen> {
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 2), (_) {
      final model = context.read<VpnModel>();
      model.refreshStats();
      model.fetchLogs();
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Consumer<VpnModel>(
      builder: (context, model, child) {
        final stats = model.blockedStats;
        final total = model.totalBlocked;

        return Scaffold(
          appBar: AppBar(
            title: const Text('Statistics'),
            centerTitle: true,
          ),
          body: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    children: [
                      Text(
                        '$total',
                        style: Theme.of(context)
                            .textTheme
                            .displayMedium
                            ?.copyWith(
                              fontWeight: FontWeight.bold,
                              color: Colors.teal,
                            ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        total == 1 ? 'Request Blocked' : 'Requests Blocked',
                        style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                              color: Colors.grey,
                            ),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),
              if (stats.isEmpty)
                const Card(
                  child: Padding(
                    padding: EdgeInsets.all(48),
                    child: Center(
                      child: Text(
                        'No data yet.\nEnable VPN to start blocking ads.',
                        textAlign: TextAlign.center,
                        style: TextStyle(color: Colors.grey),
                      ),
                    ),
                  ),
                )
              else ...[
                SizedBox(
                  height: 300,
                  child: PieChart(
                    PieChartData(
                      sections: _buildSections(stats),
                      sectionsSpace: 1,
                      centerSpaceRadius: 50,
                      centerSpaceColor: Theme.of(context).colorScheme.surface,
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Top Blocked',
                          style: TextStyle(
                            fontWeight: FontWeight.bold,
                            fontSize: 16,
                          ),
                        ),
                        const Divider(),
                        ...() {
                          final sorted = stats.entries.toList()
                            ..sort((a, b) => b.value.compareTo(a.value));
                          return sorted.take(10).map((e) => Padding(
                                padding: const EdgeInsets.symmetric(vertical: 4),
                                child: Row(
                                  children: [
                                    Expanded(
                                      child: Text(
                                        e.key,
                                        style: const TextStyle(fontSize: 13),
                                        overflow: TextOverflow.ellipsis,
                                      ),
                                    ),
                                    const SizedBox(width: 8),
                                    Text(
                                      '${e.value}',
                                      style: const TextStyle(
                                        fontWeight: FontWeight.bold,
                                      ),
                                    ),
                                  ],
                                ),
                              ));
                        }(),
                      ],
                    ),
                  ),
                ),
              ],
              const SizedBox(height: 24),
              const Divider(),
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Text(
                      'Logs',
                      style: TextStyle(
                        fontWeight: FontWeight.bold,
                        fontSize: 16,
                      ),
                    ),
                    TextButton.icon(
                      onPressed: context.read<VpnModel>().clearLogs,
                      icon: const Icon(Icons.delete_outline, size: 18),
                      label: const Text('Clear'),
                    ),
                  ],
                ),
              ),
              SizedBox(
                height: 300,
                child: ListView.builder(
                  itemCount: model.logs.length,
                  itemBuilder: (context, i) {
                    return Padding(
                      padding: const EdgeInsets.symmetric(
                          vertical: 1, horizontal: 4),
                      child: Text(
                        model.logs[i],
                        style: const TextStyle(
                          fontSize: 11,
                          fontFamily: 'monospace',
                          color: Colors.grey,
                        ),
                      ),
                    );
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  List<PieChartSectionData> _buildSections(Map<String, int> stats) {
    final entries = stats.entries.toList()
      ..sort((a, b) => b.value.compareTo(a.value));
    final top = entries.take(10).toList();
    final others = entries.skip(10).toList();

    final sections = <PieChartSectionData>[];
    final colors = Colors.primaries;

    for (var i = 0; i < top.length; i++) {
      final e = top[i];
      sections.add(PieChartSectionData(
        color: colors[i % colors.length],
        value: e.value.toDouble(),
        title: '${e.key.split('.').takeLast(2).join('.')}\n${e.value}',
        radius: 120,
        titleStyle: const TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.bold,
          color: Colors.white,
        ),
      ));
    }

    if (others.isNotEmpty) {
      final othersValue = others.fold<int>(0, (s, e) => s + e.value);
      sections.add(PieChartSectionData(
        color: Colors.grey,
        value: othersValue.toDouble(),
        title: 'Others\n$othersValue',
        radius: 120,
        titleStyle: const TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.bold,
          color: Colors.white,
        ),
      ));
    }

    return sections;
  }
}

extension _TakeLast<T> on Iterable<T> {
  Iterable<T> takeLast(int n) => toList().reversed.take(n).toList().reversed;
}
