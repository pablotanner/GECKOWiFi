import React from 'react';
import {SafeAreaView, StyleSheet, Text} from 'react-native';

export default function App() {
  return (
    <SafeAreaView style={styles.container}>
      <Text style={styles.title}>GECKOWiFi</Text>
      <Text style={styles.subtitle}>React Native integration is ready.</Text>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#f7f8fa',
  },
  title: {
    color: '#17202a',
    fontSize: 28,
    fontWeight: '700',
  },
  subtitle: {
    color: '#4f5b66',
    fontSize: 16,
    marginTop: 8,
  },
});