import 'package:flutter/material.dart';

class ToggleButton extends StatelessWidget {
  final VoidCallback onPressed;
  final bool isOn;

  const ToggleButton({Key? key, required this.onPressed, required this.isOn}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Switch(
      value: isOn,
      onChanged: (value) => onPressed(),
      activeColor: Colors.green,
      inactiveThumbColor: Colors.red,
    );
  }
}
