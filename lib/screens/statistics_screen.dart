import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:fl_chart/fl_chart.dart';
import '../models/vpn_model.dart';

class StatisticsScreen extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Consumer<VpnModel>(
      builder: (context, vpnModel, child) {
        final stats = vpnModel.blockedStats;
        final totalBlocked = stats.values.fold(0, (sum, count) => sum + count);

        return Scaffold(
          appBar: AppBar(
            title: Text('Statistics'),
            elevation: 0,
          ),
          body: Column(
            children: [
              Padding(
                padding: const EdgeInsets.all(16.0),
                child: Text(
                  'Total Blocked: $totalBlocked',
                  style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                ),
              ),
              Expanded(
                child: stats.isEmpty
                    ? Center(child: Text('No data available'))
                    : PieChart(
                        PieChartData(
                          sections: stats.entries
                              .map(
                                (entry) => PieChartSectionData(
                                  color: Colors.primaries[
                                      entry.key.hashCode % Colors.primaries.length],
                                  value: entry.value.toDouble(),
                                  title: '${entry.key}\n${entry.value}',
                                  radius: 150,
                                  titleStyle: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.white,
                                  ),
                                ),
                              )
                              .toList(),
                          sectionsSpace: 0,
                          centerSpaceRadius: 40,
                        ),
                      ),
              ),
            ],
          ),
        );
      },
    );
  }
}